# tests/conftest.py
import pathlib

import asyncpg
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.core.auth import get_current_user
from app.core.database import Database

# Tests run against a DEDICATED database so they never touch dev data.
TEST_DB_NAME = f"{settings.DB_NAME}_test"
TEST_DB_URL = (
    f"postgresql://{settings.DB_USER}:{settings.DB_PASSWORD}"
    f"@{settings.DB_HOST}:{settings.DB_PORT}/{TEST_DB_NAME}"
)

TRUNCATE_SQL = (
    "TRUNCATE feed_news, crawl_logs, crawl_data, "
    "crawler_metadata, crawl_sources, users RESTART IDENTITY CASCADE"
)

_MIGRATION_DIR = pathlib.Path(__file__).resolve().parent.parent / "db" / "migration"
_migrated = False


async def _ensure_test_db() -> None:
    """Create the test database if it does not exist."""
    admin = await asyncpg.connect(
        user=settings.DB_USER, password=settings.DB_PASSWORD,
        host=settings.DB_HOST, port=settings.DB_PORT, database="postgres",
    )
    try:
        exists = await admin.fetchval("SELECT 1 FROM pg_database WHERE datname = $1", TEST_DB_NAME)
        if not exists:
            await admin.execute(f'CREATE DATABASE "{TEST_DB_NAME}"')
    finally:
        await admin.close()


async def _ensure_schema(database: Database) -> None:
    """Apply migration SQL to the test DB once, only if the schema is missing.

    Flyway never runs in tests; the migration files are applied directly. If the
    test DB already has the schema, this is a no-op (subsequent runs just TRUNCATE).
    Add a migration → drop the test DB (DROP DATABASE scoophub_test) to re-init.
    """
    global _migrated
    if _migrated:
        return
    pool = await database.pool
    async with pool.acquire() as conn:
        already = await conn.fetchval("SELECT to_regclass('public.feed_news')")
        if already is None:
            # V 번호 순 정렬 — 문자열 정렬 시 V10 < V2 가 되어 순서 꼬임 방지
            mig_files = sorted(
                _MIGRATION_DIR.glob("V*.sql"),
                key=lambda p: int(p.name.split("__", 1)[0][1:]),
            )
            for sql_file in mig_files:
                await conn.execute(sql_file.read_text(encoding="utf-8"))
    _migrated = True


@pytest_asyncio.fixture
async def db():
    await _ensure_test_db()
    database = Database(TEST_DB_URL)
    await _ensure_schema(database)
    pool = await database.pool
    async with pool.acquire() as conn:
        await conn.execute(TRUNCATE_SQL)
    yield database
    await database.close()


@pytest.fixture(autouse=True)
def _disable_auth_bypass(monkeypatch):
    """AUTH_BYPASS는 로컬 .env(true)에 영향받지 않도록 테스트에선 항상 False.

    인증 강제 케이스(test_auth)가 .env AUTH_BYPASS=true로 우회되지 않게 격리.
    """
    monkeypatch.setattr(settings, "AUTH_BYPASS", False)


@pytest_asyncio.fixture
async def client(db):
    """FastAPI test client with clean DB state."""
    from app.main import create_app

    app = create_app(db=db)
    # 인증 의존성 우회 — 기존 도메인 테스트는 토큰 없이 동작
    app.dependency_overrides[get_current_user] = lambda: {
        "email": "test@example.com",
        "is_super": True,
    }
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        pool = await db.pool
        async with pool.acquire() as conn:
            await conn.execute(TRUNCATE_SQL)
        yield ac
