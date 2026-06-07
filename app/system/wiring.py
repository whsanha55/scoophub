# system/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class SystemModule(BaseModule):
    domain_name = "system"
    router_module = "app.system.router"
    scheduler_module = None  # scheduler 없음
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "System", "description": "시스템 상태 및 크롤 로그 API"},
    ]


register = SystemModule.register
TAGS = SystemModule.tags
