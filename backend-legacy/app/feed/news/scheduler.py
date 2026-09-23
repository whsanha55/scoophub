# news/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


async def register_jobs(scheduler: AsyncIOScheduler, db: Database) -> None:
    # 도메인 파라미터(max_lookback_hours, dedup_window_hours)는 crawl_config에서 조회.
    # _run_news_crawl이 kwargs로 받아 modify_job(kwargs=)로 런타임 갱신 가능.
    async def _run_news_crawl(
        *, max_lookback_hours: int = 24, dedup_window_hours: int = 24
    ) -> None:
        from app.feed.news.crawler import NewsCrawler

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
                from app.feed.news.dedup import llm_dedup

                async with LLMClient() as llm:
                    deduped_count = await llm_dedup(db, llm, new_ids, dedup_window_hours)
                    if deduped_count:
                        logger.info("LLM dedup marked %d duplicates", deduped_count)
            except Exception as e:
                logger.error("LLM dedup failed: %s", e)

        # Summarize non-duplicate articles
        try:
            from app.core.llm import LLMClient
            from app.feed.news.summarizer import NewsSummarizer

            async with LLMClient() as llm:
                summarizer = NewsSummarizer(db, llm)
                result = await summarizer.summarize_incomplete()
                if result["total"]:
                    logger.info("Summarized %s", result)
        except Exception as e:
            logger.error("Summarization failed: %s", e)

        # 발신 — summarizer 완료 후 (importance>=4 갱신됨). 크롤 직후가 아님.
        # base_crawler.run() 은 news 발신을 스킵하므로 여기서 명시적 dispatch.
        if crawl_result is not None:
            try:
                from app.core.notify import dispatch_crawl_notify

                await dispatch_crawl_notify(db, "news", "rss", crawl_result)
            except Exception as e:
                logger.error("news notify failed: %s", e)

    trigger, enabled = await BaseScheduler.resolve_trigger(db, "news", "news_crawler")
    params = await BaseScheduler.resolve_params(db, "news")
    scheduler.add_job(
        _run_news_crawl,
        trigger=trigger,
        kwargs=params,
        id="news_crawler",
        replace_existing=True,
    )
    if not enabled:
        scheduler.pause_job("news_crawler")
    logger.info("Scheduled job 'news_crawler' (crawler=news, enabled=%s)", enabled)
