# exchange_crypto/scheduler.py
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
    vs_currency: str = "krw",
    max_coins: int = 100,
    include_trending: bool = True,
) -> None:
    parts = schedule.split()
    if len(parts) != 5:
        raise ValueError(f"Invalid cron expression: {schedule}")

    async def _run_crawl() -> None:
        from app.exchange_crypto.crawler import ExchangeCryptoCrawler
        await ExchangeCryptoCrawler(db, vs_currency=vs_currency, max_coins=max_coins, include_trending=include_trending).run()

    scheduler.add_job(
        _run_crawl,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="exchange_crypto_crawler",
        replace_existing=True,
    )
    logger.info("Scheduled job 'exchange_crypto_crawler' with cron '%s'", schedule)
