# reddit/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class RedditModule(BaseModule):
    domain_name = "reddit"
    router_module = "app.community.reddit.router"
    scheduler_module = "app.community.reddit.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Reddit", "description": "Reddit 포스트 조회 API"},
        {"name": "Reddit Crawling", "description": "Reddit 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            client_id=cfg.get("client_id", ""),
            client_secret=cfg.get("client_secret", ""),
            user_agent=cfg.get("user_agent", "scoophub/1.0"),
            subreddits=cfg.get("subreddits"),
            listing_type=cfg.get("listing_type", "hot"),
            max_posts_per_subreddit=cfg.get("max_posts_per_subreddit", 25),
            min_score=cfg.get("min_score", 50),
        )
        return params


# main.py 호환성
register = RedditModule.register
TAGS = RedditModule.tags
