# devto_hashnode/scheduler.py
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
    tags: list[str] | None = None,
    max_articles_per_tag: int = 30,
) -> None:
    BaseScheduler.register_cron_job(
        scheduler,
        db,
        schedule=schedule,
        crawler_import="app.devto_hashnode.crawler",
        crawler_class="DevtoHashnodeCrawler",
        job_id="devto_hashnode_crawler",
        tags=tags,
        max_articles_per_tag=max_articles_per_tag,
    )
