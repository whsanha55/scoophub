# github_trending/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class GithubTrendingModule(BaseModule):
    domain_name = "github_trending"
    router_module = "app.github_trending.router"
    scheduler_module = "app.github_trending.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "GitHub Trending", "description": "GitHub 트렌딩 리포지토리 조회 API"},
        {"name": "GitHub Trending Crawling", "description": "GitHub 트렌딩 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            since=cfg.get("since", "daily"),
            language=cfg.get("language"),
            max_repos=cfg.get("max_repos", 25),
        )
        return params


# main.py 호환성
register = GithubTrendingModule.register
TAGS = GithubTrendingModule.tags
