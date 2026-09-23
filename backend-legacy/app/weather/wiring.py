# weather/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class WeatherModule(BaseModule):
    domain_name = "weather"
    router_module = "app.weather.router"
    scheduler_module = "app.weather.scheduler"
    schedule_type = "interval"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Weather", "description": "날씨 데이터 조회 API"},
        {"name": "Weather Crawling", "description": "날씨 크롤 수동 실행 API"},
    ]


# main.py 호환성
register = WeatherModule.register
TAGS = WeatherModule.tags
