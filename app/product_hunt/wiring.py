# product_hunt/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Product Hunt", "description": "Product Hunt 게시물 조회 API"},
    {"name": "Product Hunt Crawling", "description": "Product Hunt 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering product_hunt module")
    from app.product_hunt.router import router, _get_db as ph_get_db
    from app.product_hunt.scheduler import register_jobs

    ctx.app.dependency_overrides[ph_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["product_hunt"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            developer_token=cfg.get("developer_token", ""),
            max_posts=cfg.get("max_posts", 30),
        )
