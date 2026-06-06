# rss_universal/scheduler.py
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
    max_entries_per_feed: int = 50,
    respect_conditional_get: bool = True,
) -> None:
    parts = schedule.split()
    if len(parts) != 5:
        raise ValueError(f"Invalid cron expression: {schedule}")

    async def _run_crawl() -> None:
        from app.rss_universal.crawler import RssUniversalCrawler
        await RssUniversalCrawler(
            db,
            max_entries_per_feed=max_entries_per_feed,
            respect_conditional_get=respect_conditional_get,
        ).run()

    scheduler.add_job(
        _run_crawl,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="rss_universal_crawler",
        replace_existing=True,
    )
    logger.info("Scheduled job 'rss_universal_crawler' with cron '%s'", schedule)
