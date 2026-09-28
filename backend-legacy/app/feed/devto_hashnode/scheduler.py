# devto_hashnode/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


async def register_jobs(scheduler, db: Database) -> None:
    # 도메인 파라미터(tags, max_articles_per_tag)는 crawl_config에서 조회.
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="devto_hashnode",
        job_id="devto_hashnode_crawler",
        crawler_import="app.feed.devto_hashnode.crawler",
        crawler_class="DevtoHashnodeCrawler",
    )
