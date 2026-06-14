# reddit/scheduler.py
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
    client_id: str = "",
    client_secret: str = "",
    user_agent: str = "scoophub/1.0",
    subreddits: list[str] | None = None,
    listing_type: str = "hot",
    max_posts_per_subreddit: int = 25,
    min_score: int = 50,
) -> None:
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="reddit",
        job_id="reddit_crawler",
        crawler_import="app.community.reddit.crawler",
        crawler_class="RedditCrawler",
        client_id=client_id,
        client_secret=client_secret,
        user_agent=user_agent,
        subreddits=subreddits,
        listing_type=listing_type,
        max_posts_per_subreddit=max_posts_per_subreddit,
        min_score=min_score,
    )
