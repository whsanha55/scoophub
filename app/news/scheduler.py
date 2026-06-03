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
    dedup_window_hours: int = 24,
) -> None:
    async def _run_news_crawl() -> None:
        from app.news.crawler import NewsCrawler

        crawler = NewsCrawler(
            db,
            max_lookback_hours=max_lookback_hours,
        )
        crawl_result = await crawler.run()

        # LLM dedup: 신규 삽입 기사 중 중복 판별
        new_ids = crawl_result.new_article_ids if crawl_result else []
        if new_ids:
            try:
                from app.core.llm import LLMClient
                from app.news.dedup import llm_dedup

                async with LLMClient() as llm:
                    deduped_count = await llm_dedup(db, llm, new_ids, dedup_window_hours)
                    if deduped_count:
                        logger.info("LLM dedup marked %d duplicates", deduped_count)
            except Exception as e:
                logger.error("LLM dedup failed: %s", e)

        # Summarize non-duplicate articles
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
