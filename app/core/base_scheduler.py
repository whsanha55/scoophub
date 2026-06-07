from __future__ import annotations

import importlib
import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


class BaseScheduler:
    """Utility class providing shared scheduler registration logic."""

    @staticmethod
    def register_cron_job(
        scheduler: AsyncIOScheduler,
        db: Database,
        schedule: str,
        crawler_import: str,
        crawler_class: str,
        job_id: str,
        **kwargs,
    ) -> None:
        parts = schedule.split()
        if len(parts) != 5:
            raise ValueError(f"Invalid cron expression: {schedule}")

        async def _run_crawl() -> None:
            module = importlib.import_module(crawler_import)
            cls = getattr(module, crawler_class)
            await cls(db, **kwargs).run()

        scheduler.add_job(
            _run_crawl,
            trigger=CronTrigger(
                minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
            ),
            id=job_id,
            replace_existing=True,
        )
        logger.info("Scheduled job '%s' with cron '%s'", job_id, schedule)

    @staticmethod
    def register_interval_job(
        scheduler: AsyncIOScheduler,
        db: Database,
        schedule_minutes: int,
        crawler_import: str,
        crawler_class: str,
        job_id: str,
        **kwargs,
    ) -> None:
        if not isinstance(schedule_minutes, int) or schedule_minutes <= 0:
            raise ValueError(f"schedule_minutes must be a positive integer, got: {schedule_minutes}")

        async def _run_crawl() -> None:
            module = importlib.import_module(crawler_import)
            cls = getattr(module, crawler_class)
            await cls(db, **kwargs).run()

        scheduler.add_job(
            _run_crawl,
            trigger=IntervalTrigger(minutes=schedule_minutes),
            id=job_id,
            replace_existing=True,
        )
        logger.info("Scheduled job '%s' every %d minutes", job_id, schedule_minutes)
