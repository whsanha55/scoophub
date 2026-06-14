# hacker_news/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


async def register_jobs(scheduler, db: Database) -> None:
    # 도메인 파라미터(max_items, min_score, story_types)는 crawl_config에서 조회.
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="hacker_news",
        job_id="hacker_news_crawler",
        crawler_import="app.community.hacker_news.crawler",
        crawler_class="HackerNewsCrawler",
    )
