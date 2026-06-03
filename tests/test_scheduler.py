# tests/test_scheduler.py
import pytest
from unittest.mock import MagicMock

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.news.scheduler import register_jobs as news_register_jobs
from app.weather.scheduler import register_jobs as weather_register_jobs


def test_news_register_jobs_adds_job():
    scheduler = AsyncIOScheduler()
    db = MagicMock()
    news_register_jobs(scheduler, db, schedule_minutes=15, max_lookback_hours=24)
    jobs = scheduler.get_jobs()
    assert len(jobs) == 1
    assert jobs[0].id == "news_crawler"
    scheduler.remove_all_jobs()


def test_weather_register_jobs_adds_job():
    scheduler = AsyncIOScheduler()
    db = MagicMock()
    weather_register_jobs(scheduler, db, schedule_minutes=30)
    jobs = scheduler.get_jobs()
    assert len(jobs) == 1
    assert jobs[0].id == "weather_crawler"
    scheduler.remove_all_jobs()


@pytest.mark.asyncio
async def test_news_register_jobs_replace_existing():
    scheduler = AsyncIOScheduler()
    scheduler.start()
    db = MagicMock()
    news_register_jobs(scheduler, db, schedule_minutes=15, max_lookback_hours=24)
    news_register_jobs(scheduler, db, schedule_minutes=10, max_lookback_hours=12)
    jobs = scheduler.get_jobs()
    assert len(jobs) == 1
    assert jobs[0].id == "news_crawler"
    scheduler.shutdown(wait=False)
