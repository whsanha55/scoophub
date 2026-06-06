# youtube_trending/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler: AsyncIOScheduler,
    db: Database,
    schedule: str,
    api_key: str = "",
    region_codes: list[str] | None = None,
    max_results_per_region: int = 50,
) -> None:
    parts = schedule.split()
    if len(parts) != 5:
        raise ValueError(f"Invalid cron expression: {schedule}")

    async def _run_crawl() -> None:
        from app.youtube_trending.crawler import YoutubeTrendingCrawler
        await YoutubeTrendingCrawler(
            db,
            api_key=api_key,
            region_codes=region_codes,
            max_results_per_region=max_results_per_region,
        ).run()

    scheduler.add_job(
        _run_crawl,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="youtube_trending_crawler",
        replace_existing=True,
    )
    logger.info("Scheduled job 'youtube_trending_crawler' with cron '%s'", schedule)
