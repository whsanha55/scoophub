# weather/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


async def register_jobs(
    scheduler,
    db: Database,
) -> None:
    await BaseScheduler.register_interval_job(
        scheduler,
        db,
        crawler="weather",
        job_id="weather_crawler",
        crawler_import="app.weather.crawler",
        crawler_class="WeatherCrawler",
    )
