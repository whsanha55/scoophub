# youtube_trending/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class YoutubeTrendingModule(BaseModule):
    domain_name = "youtube_trending"
    router_module = "app.youtube_trending.router"
    scheduler_module = "app.youtube_trending.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "YouTube Trending", "description": "YouTube 트렌딩 영상 조회 API"},
        {"name": "YouTube Trending Crawling", "description": "YouTube 트렌딩 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            api_key=cfg.get("api_key", ""),
            region_codes=cfg.get("region_codes"),
            max_results_per_region=cfg.get("max_results_per_region", 50),
        )
        return params


# main.py 호환성
register = YoutubeTrendingModule.register
TAGS = YoutubeTrendingModule.tags
