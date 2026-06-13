# auth/router.py
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import RedirectResponse

from app.config import settings
from app.core import auth
from app.core.database import Database
from app.core.models import ApiResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/auth", tags=["Auth"])


def get_db() -> Database:
    """Placeholder — overridden in create_app."""
    raise NotImplementedError


@router.get(
    "/login",
    summary="Google OAuth 로그인 시작",
    description="Google 동의 화면으로 리다이렉트합니다. state는 HttpOnly 쿠키로 보관됩니다.",
)
async def login(request: Request) -> RedirectResponse:
    state = auth.new_state()
    url = auth.build_authorize_url(state)
    resp = RedirectResponse(url)
    resp.set_cookie(
        "oauth_state",
        state,
        httponly=True,
        samesite="lax",
        max_age=600,
        secure=not settings.OAUTH_REDIRECT_URI.startswith("http://localhost"),
    )
    logger.info("oauth login started, state set")
    return resp


@router.get(
    "/callback",
    summary="Google OAuth 콜백",
    description=(
        "Google이 리다이렉트한 code/state를 처리합니다.\n\n"
        "1. state 쿠키 검증\n"
        "2. code → access token → userinfo\n"
        "3. ALLOWED_EMAILS 체크 → 미포함 시 403\n"
        "4. users upsert (SUPER_EMAILS 매칭 시 is_super=TRUE)\n"
        "5. JWT 발급 후 AUTH_REDIRECT_URL?token=... 로 리다이렉트"
    ),
)
async def callback(
    request: Request,
    code: str,
    state: str,
    db: Database = Depends(get_db),
) -> RedirectResponse:
    cookie_state = request.cookies.get("oauth_state")
    if not cookie_state or cookie_state != state:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="invalid oauth state",
        )

    try:
        userinfo = await auth.exchange_code(code)
    except Exception as e:
        logger.warning("oauth code exchange failed: %s", e)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="oauth provider error",
        )
    email = userinfo.get("email")
    if not email:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="no email in userinfo",
        )

    if email not in settings.allowed_emails:
        logger.warning("denied login: %s", email)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="email not allowed",
        )

    is_super = await auth.upsert_user(db, email, userinfo.get("name"))
    token = auth.create_jwt(email, is_super)
    logger.info("login success: %s (super=%s)", email, is_super)

    resp = RedirectResponse(f"{settings.AUTH_REDIRECT_URL}?token={token}")
    resp.delete_cookie("oauth_state")
    return resp


@router.get(
    "/me",
    summary="내 정보 조회",
    description="JWT로 인증된 현재 사용자 정보를 반환합니다.",
)
async def me(user: dict = Depends(auth.get_current_user)) -> ApiResponse:
    return ApiResponse(success=True, data=user)
