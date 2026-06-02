# shared/scheduler.py
from __future__ import annotations

import logging
from typing import Callable, Awaitable

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger

logger = logging.getLogger(__name__)


def create_scheduler() -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler(
        job_defaults={
            "max_instances": 1,
            "misfire_grace_time": 60,
        }
    )
    return scheduler


def add_job(
    scheduler: AsyncIOScheduler,
    func: Callable[[], Awaitable[None]],
    job_id: str,
    minutes: int,
) -> None:
    scheduler.add_job(
        func,
        trigger=IntervalTrigger(minutes=minutes),
        id=job_id,
        replace_existing=True,
    )
    logger.info(f"Scheduled job '{job_id}' every {minutes} minutes")
