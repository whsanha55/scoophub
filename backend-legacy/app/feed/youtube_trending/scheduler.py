# youtube_trending/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


async def register_jobs(scheduler, db: Database) -> None:
    # 도메인 파라미터(region_codes, max_results_per_region)와 api_key는 crawl_config에서 조회.
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="youtube_trending",
        job_id="youtube_trending_crawler",
        crawler_import="app.feed.youtube_trending.crawler",
        crawler_class="YoutubeTrendingCrawler",
    )
