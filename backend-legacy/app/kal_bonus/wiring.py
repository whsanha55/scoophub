# app/kal_bonus/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class KalBonusModule(BaseModule):
    domain_name = "kal_bonus"
    router_module = "app.kal_bonus.router"
    scheduler_module = "app.kal_bonus.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "KAL Bonus Seat", "description": "대한항공 보너스 좌석 현황 조회 API"},
        {"name": "KAL Bonus Seat Crawling", "description": "대한항공 보너스 좌석 크롤 수동 실행 API"},
    ]


# main.py 호환성
register = KalBonusModule.register
TAGS = KalBonusModule.tags
