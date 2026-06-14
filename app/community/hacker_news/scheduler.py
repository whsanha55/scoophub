# hacker_news/scheduler.py
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
    max_items: int = 100,
    min_score: int = 50,
    story_types: list[str] | None = None,
) -> None:
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="hacker_news",
        job_id="hacker_news_crawler",
        crawler_import="app.community.hacker_news.crawler",
        crawler_class="HackerNewsCrawler",
        max_items=max_items,
        min_score=min_score,
        story_types=story_types,
    )
