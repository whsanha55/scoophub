# github_trending/scheduler.py
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
    since: str = "daily",
    language: str | None = None,
    max_repos: int = 25,
) -> None:
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="github_trending",
        job_id="github_trending_crawler",
        crawler_import="app.community.github_trending.crawler",
        crawler_class="GithubTrendingCrawler",
        since=since,
        language=language,
        max_repos=max_repos,
    )
