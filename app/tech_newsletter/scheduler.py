# tech_newsletter/scheduler.py
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
    feeds: list[dict] | None = None,
) -> None:
    BaseScheduler.register_cron_job(
        scheduler,
        db,
        schedule=schedule,
        crawler_import="app.tech_newsletter.crawler",
        crawler_class="TechNewsletterCrawler",
        job_id="tech_newsletter_crawler",
        feeds=feeds,
    )
