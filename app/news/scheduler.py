# news/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler: AsyncIOScheduler,
    db: Database,
    schedule_minutes: int,
    max_lookback_hours: int = 24,
    title_similarity: float = 0.85,
    dedup_window_hours: int = 24,
) -> None:
    async def _run_news_crawl() -> None:
        from app.news.crawler import NewsCrawler

        await NewsCrawler(
            db,
            max_lookback_hours=max_lookback_hours,
            title_similarity=title_similarity,
            dedup_window_hours=dedup_window_hours,
        ).run()

        # Summarize newly crawled articles
        try:
            from app.core.llm import LLMClient
            from app.news.summarizer import NewsSummarizer

            async with LLMClient() as llm:
                summarizer = NewsSummarizer(db, llm)
                result = await summarizer.summarize_incomplete()
                if result["total"]:
                    logger.info("Summarized %s", result)
        except Exception as e:
            logger.error("Summarization failed: %s", e)

    scheduler.add_job(
        _run_news_crawl,
        trigger=IntervalTrigger(minutes=schedule_minutes),
        id="news_crawler",
        replace_existing=True,
    )
    logger.info(f"Scheduled job 'news_crawler' every {schedule_minutes} minutes")
