# hacker_news/scheduler.py
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
    max_items: int = 100,
    min_score: int = 50,
    story_types: list[str] | None = None,
) -> None:
    parts = schedule.split()
    if len(parts) != 5:
        raise ValueError(f"Invalid cron expression: {schedule}")

    async def _run_crawl() -> None:
        from app.hacker_news.crawler import HackerNewsCrawler
        await HackerNewsCrawler(db, max_items=max_items, min_score=min_score, story_types=story_types).run()

    scheduler.add_job(
        _run_crawl,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="hacker_news_crawler",
        replace_existing=True,
    )
    logger.info("Scheduled job 'hacker_news_crawler' with cron '%s'", schedule)
