# tech_newsletter/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Tech Newsletter", "description": "Tech Newsletter 아티클 조회 API"},
    {"name": "Tech Newsletter Crawling", "description": "Tech Newsletter 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering tech_newsletter module")
    from app.tech_newsletter.router import router, _get_db as tn_get_db
    from app.tech_newsletter.scheduler import register_jobs

    ctx.app.dependency_overrides[tn_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["tech_newsletter"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            feeds=cfg.get("feeds"),
        )
