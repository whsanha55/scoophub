# app/kal_bonus/scheduler.py
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
    schedule: str,
) -> None:
    """KAL 보너스 좌석 크롤 스케줄 등록 (기본 1일 1회, KST 07:00)."""
    BaseScheduler.register_cron_job(
        scheduler,
        db,
        schedule=schedule,
        crawler_import="app.kal_bonus.crawler",
        crawler_class="KalBonusCrawler",
        job_id="kal_bonus_crawler",
    )
