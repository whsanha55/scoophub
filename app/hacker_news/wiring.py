# hacker_news/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Hacker News", "description": "Hacker News 아이템 조회 API"},
    {"name": "Hacker News Crawling", "description": "Hacker News 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering hacker_news module")
    from app.hacker_news.router import router, _get_db as hn_get_db
    from app.hacker_news.scheduler import register_jobs

    ctx.app.dependency_overrides[hn_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["hacker_news"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            max_items=cfg.get("max_items", 100),
            min_score=cfg.get("min_score", 50),
            story_types=cfg.get("story_types"),
        )
