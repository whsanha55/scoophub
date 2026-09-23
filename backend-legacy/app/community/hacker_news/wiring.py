# hacker_news/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class HackerNewsModule(BaseModule):
    domain_name = "hacker_news"
    router_module = "app.community.hacker_news.router"
    scheduler_module = "app.community.hacker_news.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Hacker News", "description": "Hacker News 아이템 조회 API"},
        {"name": "Hacker News Crawling", "description": "Hacker News 크롤 수동 실행 API"},
    ]



# main.py 호환성
register = HackerNewsModule.register
TAGS = HackerNewsModule.tags
