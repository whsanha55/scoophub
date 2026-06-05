# jira/scheduler.py
from __future__ import annotations

import logging

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from app.core.database import Database

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler: AsyncIOScheduler,
    db: Database,
    schedule: str = "0 9 * * 1",
    max_results: int = 100,
) -> None:
    from app.jira.crawler import JiraWeeklyCrawler

    async def _weekly_fetch() -> None:
        logger.info("Jira weekly fetch started")
        try:
            crawler = JiraWeeklyCrawler(db)
            result = await crawler.run(max_results=max_results)
            logger.info(
                "Jira weekly fetch done: issues=%d, comments=%d, summary=%s, topics=%d",
                result.issues_fetched, result.comments_fetched,
                result.summary_id, result.topics_classified,
            )
        except Exception:
            logger.exception("Jira weekly fetch failed")

    async def _retry_pending() -> None:
        from app.jira.repository import PendingRetryRepo

        repo = PendingRetryRepo(db)
        pending = await repo.find_pending()
        if not pending:
            return

        logger.info("Retrying %d pending Jira operations", len(pending))
        for entry in pending:
            await repo.mark_running(entry.id)
            try:
                crawler = JiraWeeklyCrawler(db)
                result = await crawler.run()
                logger.info("Retry %d succeeded: %s", entry.id, result)
                await repo.mark_done(entry.id)
            except Exception as e:
                logger.warning("Retry %d failed: %s", entry.id, e)
                await repo.increment_retry(entry.id, str(e))

    # 주간 크롤 — 월요일 09:00
    parts = schedule.split()
    scheduler.add_job(
        _weekly_fetch,
        trigger=CronTrigger(
            minute=parts[0], hour=parts[1],
            day=parts[2], month=parts[3], day_of_week=parts[4],
        ),
        id="jira_weekly_fetch",
        replace_existing=True,
    )

    # 재시도 — 30분 간격
    scheduler.add_job(
        _retry_pending,
        trigger=IntervalTrigger(minutes=30),
        id="jira_summary_retry",
        replace_existing=True,
    )

    logger.info("Jira scheduler registered: fetch=[%s], retry=30min", schedule)
