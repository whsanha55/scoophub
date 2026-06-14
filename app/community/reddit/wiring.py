# reddit/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

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



# main.py 호환성
register = RedditModule.register
TAGS = RedditModule.tags
