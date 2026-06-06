# youtube_trending/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "YouTube Trending", "description": "YouTube 트렌딩 영상 조회 API"},
    {"name": "YouTube Trending Crawling", "description": "YouTube 트렌딩 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering youtube_trending module")
    from app.youtube_trending.router import router, _get_db as yt_get_db
    from app.youtube_trending.scheduler import register_jobs

    ctx.app.dependency_overrides[yt_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["youtube_trending"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            api_key=cfg.get("api_key", ""),
            region_codes=cfg.get("region_codes"),
            max_results_per_region=cfg.get("max_results_per_region", 50),
        )
