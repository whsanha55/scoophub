# core/auth.py
"""Google OAuth + JWT 인증 코어.

- Google OAuth Authorization Code flow (authlib)
- JWT 발급/검증 (python-jose)
- FastAPI 의존성: get_current_user / get_super_user
- users 테이블 upsert (SUPER_EMAILS 매칭 시 is_super=TRUE)
"""
from __future__ import annotations

import logging
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any

from authlib.integrations.httpx_client import AsyncOAuth2Client
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt

from app.config import settings
from app.core.database import Database

logger = logging.getLogger(__name__)

GOOGLE_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
OAUTH_SCOPE = "openid email profile"

bearer_scheme = HTTPBearer(auto_error=False)


# ── OAuth state + authorize URL ────────────────────────────

def new_state() -> str:
    """CSRF 방지용 난수 state 생성."""
    return secrets.token_urlsafe(32)


def build_authorize_url(state: str) -> str:
    """Google 동의 화면 URL 생성."""
    client = AsyncOAuth2Client(
        client_id=settings.GOOGLE_CLIENT_ID,
        redirect_uri=settings.OAUTH_REDIRECT_URI,
        scope=OAUTH_SCOPE,
    )
    url, _ = client.create_authorization_url(
        GOOGLE_AUTHORIZE_URL, state=state
    )
    return url


# ── code → userinfo ────────────────────────────────────────

async def exchange_code(code: str) -> dict[str, Any]:
    """authorization code → access token → Google userinfo."""
    async with AsyncOAuth2Client(
        client_id=settings.GOOGLE_CLIENT_ID,
        client_secret=settings.GOOGLE_CLIENT_SECRET,
        redirect_uri=settings.OAUTH_REDIRECT_URI,
    ) as client:
        token = await client.fetch_token(GOOGLE_TOKEN_URL, code=code)
        resp = await client.get(GOOGLE_USERINFO_URL, token=token)
        resp.raise_for_status()
        return resp.json()


# ── users upsert ───────────────────────────────────────────

async def upsert_user(db: Database, email: str, name: str | None) -> bool:
    """users 테이블 upsert. SUPER_EMAILS 매칭 시 is_super=TRUE. is_super 반환."""
    is_super = email in settings.super_emails
    await db.execute(
        """
        INSERT INTO users (email, name, is_super, last_login_at)
        VALUES ($1, $2, $3, NOW())
        ON CONFLICT (email) DO UPDATE
        SET name = EXCLUDED.name,
            is_super = EXCLUDED.is_super,
            last_login_at = NOW()
        """,
        email, name, is_super,
    )
    return is_super


# ── JWT ────────────────────────────────────────────────────

def create_jwt(email: str, is_super: bool) -> str:
    """JWT 발급: {sub: email, is_super, iat, exp}."""
    now = datetime.now(timezone.utc)
    payload = {
        "sub": email,
        "is_super": is_super,
        "iat": now,
        "exp": now + timedelta(hours=settings.JWT_EXPIRE_HOURS),
    }
    return jwt.encode(payload, settings.JWT_SECRET, algorithm="HS256")


def decode_jwt(token: str) -> dict[str, Any]:
    """JWT 검증. 실패 시 401."""
    try:
        return jwt.decode(token, settings.JWT_SECRET, algorithms=["HS256"])
    except JWTError as e:
        logger.warning("jwt decode failed: %s", e)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid or expired token",
        )


# ── FastAPI 의존성 ─────────────────────────────────────────

async def get_current_user(
    cred: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> dict[str, Any]:
    """Bearer 토큰 → 사용자 정보({email, is_super})."""
    if cred is None or not cred.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="not authenticated",
        )
    payload = decode_jwt(cred.credentials)
    return {"email": payload["sub"], "is_super": payload.get("is_super", False)}


async def get_super_user(
    user: dict[str, Any] = Depends(get_current_user),
) -> dict[str, Any]:
    """is_super=True 사용자만 통과."""
    if not user["is_super"]:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="super user only",
        )
    return user
