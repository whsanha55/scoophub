# rss_universal/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "RSS Universal", "description": "RSS 피드 및 엔트리 조회 API"},
    {"name": "RSS Universal Crawling", "description": "RSS Universal 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering rss_universal module")
    from app.rss_universal.router import router, _get_db as ru_get_db
    from app.rss_universal.scheduler import register_jobs

    ctx.app.dependency_overrides[ru_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["rss_universal"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            max_entries_per_feed=cfg.get("max_entries_per_feed", 50),
            respect_conditional_get=cfg.get("respect_conditional_get", True),
        )
