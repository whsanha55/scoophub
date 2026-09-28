# devto_hashnode/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

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



# main.py 호환성
register = DevtoHashnodeModule.register
TAGS = DevtoHashnodeModule.tags
