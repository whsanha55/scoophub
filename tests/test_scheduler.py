# tests/test_scheduler.py
import pytest
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from app.community.github_trending.scheduler import register_jobs as gh_register_jobs
from app.core.base_scheduler import BaseScheduler
from app.feed.news.scheduler import register_jobs as news_register_jobs
from app.weather.scheduler import register_jobs as weather_register_jobs


# --- resolve_trigger 단위 (DB seed 필요) ---


async def test_resolve_trigger_cron_single(db):
    trigger, enabled = await BaseScheduler.resolve_trigger(db, "github_trending", "github_trending_crawler")
    assert enabled is True
    assert isinstance(trigger, CronTrigger)


async def test_resolve_trigger_interval(db):
    trigger, enabled = await BaseScheduler.resolve_trigger(db, "news", "news_crawler")
    assert enabled is True
    assert isinstance(trigger, IntervalTrigger)


async def test_resolve_trigger_missing_raises(db):
    with pytest.raises(ValueError):
        await BaseScheduler.resolve_trigger(db, "nonexistent", "nope")


# --- register_jobs (DB seed 필요) ---


async def test_news_register_jobs_adds_job(db):
    scheduler = AsyncIOScheduler()
    await news_register_jobs(scheduler, db, max_lookback_hours=24)
    jobs = scheduler.get_jobs()
    assert len(jobs) == 1
    assert jobs[0].id == "news_crawler"


async def test_weather_register_jobs_adds_job(db):
    scheduler = AsyncIOScheduler()
    await weather_register_jobs(scheduler, db)
    jobs = scheduler.get_jobs()
    assert len(jobs) == 1
    assert jobs[0].id == "weather_crawler"


async def test_cron_register_jobs_adds_job(db):
    scheduler = AsyncIOScheduler()
    await gh_register_jobs(scheduler, db, since="daily", max_repos=10)
    jobs = scheduler.get_jobs()
    assert len(jobs) == 1
    assert jobs[0].id == "github_trending_crawler"


async def test_news_register_jobs_replace_existing(db):
    scheduler = AsyncIOScheduler()
    scheduler.start()
    await news_register_jobs(scheduler, db, max_lookback_hours=24)
    await news_register_jobs(scheduler, db, max_lookback_hours=12)
    jobs = scheduler.get_jobs()
    assert len(jobs) == 1
    assert jobs[0].id == "news_crawler"
    scheduler.shutdown(wait=False)


async def test_disabled_job_is_paused(db):
    """enabled=False 행은 add_job 직후 pause 처리."""
    await db.execute(
        "UPDATE crawl_schedule SET enabled=FALSE WHERE crawler='weather' AND job_id='weather_crawler'"
    )
    scheduler = AsyncIOScheduler()
    scheduler.start()
    await weather_register_jobs(scheduler, db)
    job = scheduler.get_job("weather_crawler")
    assert job is not None
    assert job.next_run_time is None  # paused
    scheduler.shutdown(wait=False)
