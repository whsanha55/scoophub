# arxiv/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "arXiv", "description": "arXiv 논문 조회 API"},
    {"name": "arXiv Crawling", "description": "arXiv 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering arxiv module")
    from app.arxiv.router import router, _get_db as arxiv_get_db
    from app.arxiv.scheduler import register_jobs

    ctx.app.dependency_overrides[arxiv_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg.get("crawlers", {}).get("arxiv", {})
        schedule = cfg.get("schedule")
        if schedule:
            register_jobs(
                ctx.scheduler,
                ctx.db,
                schedule=schedule,
                categories=cfg.get("categories"),
                max_results_per_category=cfg.get("max_results_per_category", 25),
            )
