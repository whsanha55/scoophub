# tech_newsletter/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class TechNewsletterModule(BaseModule):
    domain_name = "tech_newsletter"
    router_module = "app.feed.tech_newsletter.router"
    scheduler_module = "app.feed.tech_newsletter.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Tech Newsletter", "description": "Tech Newsletter 아티클 조회 API"},
        {"name": "Tech Newsletter Crawling", "description": "Tech Newsletter 크롤 수동 실행 API"},
    ]



# main.py 호환성
register = TechNewsletterModule.register
TAGS = TechNewsletterModule.tags
