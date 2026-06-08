# youtube_trending/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler,
    db: Database,
    schedule: str,
    api_key: str = "",
    region_codes: list[str] | None = None,
    max_results_per_region: int = 50,
) -> None:
    BaseScheduler.register_cron_job(
        scheduler,
        db,
        schedule=schedule,
        crawler_import="app.feed.youtube_trending.crawler",
        crawler_class="YoutubeTrendingCrawler",
        job_id="youtube_trending_crawler",
        api_key=api_key,
        region_codes=region_codes,
        max_results_per_region=max_results_per_region,
    )
