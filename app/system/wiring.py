# system/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "System", "description": "시스템 상태 및 크롤 로그 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering system module")
    from app.system.router import router, get_db

    ctx.app.dependency_overrides[get_db] = lambda: ctx.db
    ctx.app.include_router(router)
