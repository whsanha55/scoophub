# devto_hashnode/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class DevtoHashnodeModule(BaseModule):
    domain_name = "devto_hashnode"
    router_module = "app.feed.devto_hashnode.router"
    scheduler_module = "app.feed.devto_hashnode.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Dev.to", "description": "Dev.to 트렌딩 아티클 조회 API"},
        {"name": "Dev.to Crawling", "description": "Dev.to 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            tags=cfg.get("tags"),
            max_articles_per_tag=cfg.get("max_articles_per_tag", 30),
        )
        return params


# main.py 호환성
register = DevtoHashnodeModule.register
TAGS = DevtoHashnodeModule.tags
