# tests/test_auth.py
import pytest
import pytest_asyncio
from fastapi import HTTPException
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.core import auth


@pytest_asyncio.fixture
async def real_client(db):
    """인증 override 없는 클라이언트 — 실제 인증 강제 여부 검증."""
    from app.main import create_app
    app = create_app(db=db)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture(autouse=True)
def allow_test_emails(monkeypatch):
    monkeypatch.setattr(settings, "ALLOWED_EMAILS", "alice@example.com,bob@example.com")
    monkeypatch.setattr(settings, "SUPER_EMAILS", "alice@example.com")


# ── JWT 단위 ────────────────────────────────────────────

def test_jwt_roundtrip():
    token = auth.create_jwt("x@y.com", True)
    payload = auth.decode_jwt(token)
    assert payload["sub"] == "x@y.com"
    assert payload["is_super"] is True


def test_decode_invalid_token():
    with pytest.raises(HTTPException) as exc:
        auth.decode_jwt("not-a-jwt")
    assert exc.value.status_code == 401


# ── users upsert 단위 ───────────────────────────────────

async def test_upsert_marks_super(db):
    is_super = await auth.upsert_user(db, "alice@example.com", "Alice")
    assert is_super is True
    row = await db.fetchrow("SELECT is_super FROM users WHERE email=$1", "alice@example.com")
    assert row["is_super"] is True


async def test_upsert_marks_normal(db):
    is_super = await auth.upsert_user(db, "bob@example.com", "Bob")
    assert is_super is False
    row = await db.fetchrow("SELECT is_super FROM users WHERE email=$1", "bob@example.com")
    assert row["is_super"] is False


async def test_upsert_idempotent(db):
    await auth.upsert_user(db, "alice@example.com", "Alice")
    await auth.upsert_user(db, "alice@example.com", "Alice2")
    count = await db.fetchval("SELECT COUNT(*) FROM users WHERE email=$1", "alice@example.com")
    assert count == 1


async def test_get_super_user_denies_normal():
    user = {"email": "bob@example.com", "is_super": False}
    with pytest.raises(HTTPException) as exc:
        await auth.get_super_user(user)
    assert exc.value.status_code == 403


async def test_get_super_user_allows_super():
    user = {"email": "alice@example.com", "is_super": True}
    result = await auth.get_super_user(user)
    assert result["is_super"] is True


# ── 라우트 보호/공개 ───────────────────────────────────

async def test_health_is_public(real_client):
    resp = await real_client.get("/api/health")
    assert resp.status_code == 200


async def test_get_routes_are_public(real_client):
    # GET 조회 엔드포인트는 인증 없이 200 (이슈 #139)
    for path in ("/api/news", "/api/stock/watchlist", "/api/schedules"):
        resp = await real_client.get(path)
        assert resp.status_code == 200, f"{path} should be public: {resp.status_code}"


async def test_mutation_requires_token(real_client):
    # mutation 엔드포인트는 비인가 시 401 (이슈 #139)
    resp = await real_client.post("/api/crawling/news")
    assert resp.status_code == 401


async def test_mutation_denies_non_super(real_client):
    # 일반 사용자(is_super=False)는 mutation 시 403 (이슈 #139)
    token = auth.create_jwt("bob@example.com", False)
    resp = await real_client.post(
        "/api/crawling/news", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 403


async def test_me_requires_token(real_client):
    resp = await real_client.get("/api/auth/me")
    assert resp.status_code == 401


async def test_me_with_valid_token(real_client):
    token = auth.create_jwt("alice@example.com", True)
    resp = await real_client.get(
        "/api/auth/me", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["data"]["email"] == "alice@example.com"
    assert body["data"]["is_super"] is True


# ── OAuth 로그인 흐름 ──────────────────────────────────

async def test_login_redirects_to_google(real_client):
    resp = await real_client.get("/api/auth/login")
    assert resp.status_code in (302, 307)
    assert "accounts.google.com" in resp.headers["location"]


async def test_callback_invalid_state(real_client):
    resp = await real_client.get(
        "/api/auth/callback",
        params={"code": "c", "state": "x"},
        cookies={"oauth_state": "y"},
    )
    assert resp.status_code == 400


async def test_callback_denies_non_allowed_email(real_client, monkeypatch):
    async def fake_exchange(code):
        return {"email": "stranger@example.com", "name": "Stranger"}
    monkeypatch.setattr(auth, "exchange_code", fake_exchange)
    resp = await real_client.get(
        "/api/auth/callback",
        params={"code": "c", "state": "s"},
        cookies={"oauth_state": "s"},
    )
    assert resp.status_code == 403


async def test_callback_success_issues_token(real_client, monkeypatch, db):
    async def fake_exchange(code):
        return {"email": "alice@example.com", "name": "Alice"}
    monkeypatch.setattr(auth, "exchange_code", fake_exchange)
    resp = await real_client.get(
        "/api/auth/callback",
        params={"code": "c", "state": "s"},
        cookies={"oauth_state": "s"},
    )
    assert resp.status_code in (302, 307)
    assert "token=" in resp.headers["location"]

    row = await db.fetchrow(
        "SELECT name, is_super FROM users WHERE email=$1", "alice@example.com"
    )
    assert row is not None
    assert row["name"] == "Alice"
    assert row["is_super"] is True
