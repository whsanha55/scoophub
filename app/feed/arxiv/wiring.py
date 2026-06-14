# arxiv/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class ArxivModule(BaseModule):
    domain_name = "arxiv"
    router_module = "app.feed.arxiv.router"
    scheduler_module = "app.feed.arxiv.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "arXiv", "description": "arXiv 논문 조회 API"},
        {"name": "arXiv Crawling", "description": "arXiv 크롤 수동 실행 API"},
    ]



# main.py 호환성
register = ArxivModule.register
TAGS = ArxivModule.tags
