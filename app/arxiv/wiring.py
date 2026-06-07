# arxiv/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class ArxivModule(BaseModule):
    domain_name = "arxiv"
    router_module = "app.arxiv.router"
    scheduler_module = "app.arxiv.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "arXiv", "description": "arXiv 논문 조회 API"},
        {"name": "arXiv Crawling", "description": "arXiv 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            categories=cfg.get("categories"),
            max_results_per_category=cfg.get("max_results_per_category", 25),
        )
        return params


# main.py 호환성
register = ArxivModule.register
TAGS = ArxivModule.tags
