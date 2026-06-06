# weather/scheduler.py
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
    schedule_minutes: int,
) -> None:
    BaseScheduler.register_interval_job(
        scheduler,
        db,
        schedule_minutes=schedule_minutes,
        crawler_import="app.weather.crawler",
        crawler_class="WeatherCrawler",
        job_id="weather_crawler",
    )
