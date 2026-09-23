# github_trending/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class GithubTrendingModule(BaseModule):
    domain_name = "github_trending"
    router_module = "app.community.github_trending.router"
    scheduler_module = "app.community.github_trending.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "GitHub Trending", "description": "GitHub 트렌딩 리포지토리 조회 API"},
        {"name": "GitHub Trending Crawling", "description": "GitHub 트렌딩 크롤 수동 실행 API"},
    ]



# main.py 호환성
register = GithubTrendingModule.register
TAGS = GithubTrendingModule.tags
