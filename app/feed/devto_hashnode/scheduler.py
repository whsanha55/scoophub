# devto_hashnode/scheduler.py
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
    tags: list[str] | None = None,
    max_articles_per_tag: int = 30,
) -> None:
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="devto_hashnode",
        job_id="devto_hashnode_crawler",
        crawler_import="app.feed.devto_hashnode.crawler",
        crawler_class="DevtoHashnodeCrawler",
        tags=tags,
        max_articles_per_tag=max_articles_per_tag,
    )
