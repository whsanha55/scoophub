# news/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler: AsyncIOScheduler,
    db: Database,
    schedule_minutes: int,
    cutoff_minutes: int,
) -> None:
    async def _run_news_crawl() -> None:
        from app.news.crawler import NewsCrawler

        await NewsCrawler(db, cutoff_minutes=cutoff_minutes).run()

    scheduler.add_job(
        _run_news_crawl,
        trigger=IntervalTrigger(minutes=schedule_minutes),
        id="news_crawler",
        replace_existing=True,
    )
    logger.info(f"Scheduled job 'news_crawler' every {schedule_minutes} minutes")
