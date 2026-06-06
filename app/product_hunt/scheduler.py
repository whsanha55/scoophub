# product_hunt/scheduler.py
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
    developer_token: str = "",
    max_posts: int = 30,
) -> None:
    parts = schedule.split()
    if len(parts) != 5:
        raise ValueError(f"Invalid cron expression: {schedule}")

    async def _run_crawl() -> None:
        from app.product_hunt.crawler import ProductHuntCrawler
        await ProductHuntCrawler(db, developer_token=developer_token, max_posts=max_posts).run()

    scheduler.add_job(
        _run_crawl,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="product_hunt_crawler",
        replace_existing=True,
    )
    logger.info("Scheduled job 'product_hunt_crawler' with cron '%s'", schedule)
