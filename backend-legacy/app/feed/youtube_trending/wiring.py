# youtube_trending/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class YoutubeTrendingModule(BaseModule):
    domain_name = "youtube_trending"
    router_module = "app.feed.youtube_trending.router"
    scheduler_module = "app.feed.youtube_trending.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "YouTube Trending", "description": "YouTube 트렌딩 영상 조회 API"},
        {"name": "YouTube Trending Crawling", "description": "YouTube 트렌딩 크롤 수동 실행 API"},
    ]



# main.py 호환성
register = YoutubeTrendingModule.register
TAGS = YoutubeTrendingModule.tags
