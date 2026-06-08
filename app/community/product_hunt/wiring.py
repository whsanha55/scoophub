# product_hunt/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class ProductHuntModule(BaseModule):
    domain_name = "product_hunt"
    router_module = "app.community.product_hunt.router"
    scheduler_module = "app.community.product_hunt.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Product Hunt", "description": "Product Hunt 게시물 조회 API"},
        {"name": "Product Hunt Crawling", "description": "Product Hunt 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            developer_token=cfg.get("developer_token", ""),
            max_posts=cfg.get("max_posts", 30),
        )
        return params


# main.py 호환성
register = ProductHuntModule.register
TAGS = ProductHuntModule.tags
