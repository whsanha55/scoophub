# github_trending/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "GitHub Trending", "description": "GitHub 트렌딩 리포지토리 조회 API"},
    {"name": "GitHub Trending Crawling", "description": "GitHub 트렌딩 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering github_trending module")
    from app.github_trending.router import router, _get_db as gt_get_db
    from app.github_trending.scheduler import register_jobs

    ctx.app.dependency_overrides[gt_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["github_trending"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            since=cfg.get("since", "daily"),
            language=cfg.get("language"),
            max_repos=cfg.get("max_repos", 25),
        )
