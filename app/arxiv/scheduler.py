# arxiv/scheduler.py
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
    categories: list[str] | None = None,
    max_results_per_category: int = 25,
) -> None:
    parts = schedule.split()
    if len(parts) != 5:
        raise ValueError(f"Invalid cron expression: {schedule}")

    async def _run_crawl() -> None:
        from app.arxiv.crawler import ArxivCrawler
        await ArxivCrawler(db, categories=categories, max_results_per_category=max_results_per_category).run()

    scheduler.add_job(
        _run_crawl,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="arxiv_crawler",
        replace_existing=True,
    )
    logger.info("Scheduled job 'arxiv_crawler' with cron '%s'", schedule)
