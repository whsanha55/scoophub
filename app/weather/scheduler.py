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
) -> None:
    async def _run_weather_crawl() -> None:
        from app.weather.crawler import WeatherCrawler
        await WeatherCrawler(db).run()

    scheduler.add_job(
        _run_weather_crawl,
        trigger=IntervalTrigger(minutes=schedule_minutes),
        id="weather_crawler",
        replace_existing=True,
    )
    logger.info(f"Scheduled job 'weather_crawler' every {schedule_minutes} minutes")
