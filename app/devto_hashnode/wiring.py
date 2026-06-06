# devto_hashnode/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Dev.to", "description": "Dev.to 트렌딩 아티클 조회 API"},
    {"name": "Dev.to Crawling", "description": "Dev.to 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering devto_hashnode module")
    from app.devto_hashnode.router import router, _get_db as dh_get_db
    from app.devto_hashnode.scheduler import register_jobs

    ctx.app.dependency_overrides[dh_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["devto_hashnode"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            tags=cfg.get("tags"),
            max_articles_per_tag=cfg.get("max_articles_per_tag", 30),
        )
