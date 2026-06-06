# tech_newsletter/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class TechNewsletterModule(BaseModule):
    domain_name = "tech_newsletter"
    router_module = "app.tech_newsletter.router"
    scheduler_module = "app.tech_newsletter.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Tech Newsletter", "description": "Tech Newsletter 아티클 조회 API"},
        {"name": "Tech Newsletter Crawling", "description": "Tech Newsletter 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            feeds=cfg.get("feeds"),
        )
        return params


# main.py 호환성
register = TechNewsletterModule.register
TAGS = TechNewsletterModule.tags
