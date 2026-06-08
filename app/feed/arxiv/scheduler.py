# arxiv/scheduler.py
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
    categories: list[str] | None = None,
    max_results_per_category: int = 25,
) -> None:
    BaseScheduler.register_cron_job(
        scheduler,
        db,
        schedule=schedule,
        crawler_import="app.feed.arxiv.crawler",
        crawler_class="ArxivCrawler",
        job_id="arxiv_crawler",
        categories=categories,
        max_results_per_category=max_results_per_category,
    )
