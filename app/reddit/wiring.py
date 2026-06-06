# reddit/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Reddit", "description": "Reddit 포스트 조회 API"},
    {"name": "Reddit Crawling", "description": "Reddit 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering reddit module")
    from app.reddit.router import router, _get_db as reddit_get_db
    from app.reddit.scheduler import register_jobs

    ctx.app.dependency_overrides[reddit_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["reddit"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            client_id=cfg.get("client_id", ""),
            client_secret=cfg.get("client_secret", ""),
            user_agent=cfg.get("user_agent", "scoophub/1.0"),
            subreddits=cfg.get("subreddits"),
            listing_type=cfg.get("listing_type", "hot"),
            max_posts_per_subreddit=cfg.get("max_posts_per_subreddit", 25),
            min_score=cfg.get("min_score", 50),
        )
