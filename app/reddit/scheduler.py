# reddit/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler: AsyncIOScheduler,
    db: Database,
    schedule: str,
    client_id: str = "",
    client_secret: str = "",
    user_agent: str = "scoophub/1.0",
    subreddits: list[str] | None = None,
    listing_type: str = "hot",
    max_posts_per_subreddit: int = 25,
    min_score: int = 50,
) -> None:
    parts = schedule.split()
    if len(parts) != 5:
        raise ValueError(f"Invalid cron expression: {schedule}")

    async def _run_crawl() -> None:
        from app.reddit.crawler import RedditCrawler
        await RedditCrawler(
            db,
            client_id=client_id,
            client_secret=client_secret,
            user_agent=user_agent,
            subreddits=subreddits,
            listing_type=listing_type,
            max_posts_per_subreddit=max_posts_per_subreddit,
            min_score=min_score,
        ).run()

    scheduler.add_job(
        _run_crawl,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="reddit_crawler",
        replace_existing=True,
    )
    logger.info("Scheduled job 'reddit_crawler' with cron '%s'", schedule)
