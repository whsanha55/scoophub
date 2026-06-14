# tests/test_schedules_router.py
import pytest
from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.community.github_trending.scheduler import register_jobs as gh_register_jobs
from app.system.schedules_router import ScheduleUpdate, get_schedule, list_schedules, update_schedule


async def _make_scheduler(db) -> AsyncIOScheduler:
    """crawl_schedule seed 기반으로 job들을 실제 등록한 scheduler 반환."""
    scheduler = AsyncIOScheduler()
    scheduler.start()
    await gh_register_jobs(scheduler, db, since="daily", max_repos=10)
    return scheduler


async def test_list_schedules_returns_seed_rows(db):
    scheduler = await _make_scheduler(db)
    try:
        resp = await list_schedules(db=db, scheduler=scheduler)
        assert resp.success
        assert resp.meta.total == 15
        crawlers = {row["crawler"] for row in resp.data}
        assert "stock" in crawlers and "news" in crawlers
    finally:
        scheduler.shutdown(wait=False)


async def test_get_schedule_single(db):
    scheduler = await _make_scheduler(db)
    try:
        resp = await get_schedule("github_trending", "github_trending_crawler", db=db, scheduler=scheduler)
        assert resp.success
        assert resp.data["crawler"] == "github_trending"
        assert resp.data["schedule_type"] == "cron"
    finally:
        scheduler.shutdown(wait=False)


async def test_get_schedule_not_found(db):
    scheduler = await _make_scheduler(db)
    try:
        with pytest.raises(Exception):
            await get_schedule("nope", "nope", db=db, scheduler=scheduler)
    finally:
        scheduler.shutdown(wait=False)


async def test_update_schedule_pause_resume(db):
    scheduler = await _make_scheduler(db)
    try:
        # disable → pause
        resp = await update_schedule(
            "github_trending", "github_trending_crawler",
            ScheduleUpdate(enabled=False), db=db, scheduler=scheduler,
        )
        assert resp.data["paused"] is True
        job = scheduler.get_job("github_trending_crawler")
        assert job.next_run_time is None  # paused

        # re-enable → resume
        resp = await update_schedule(
            "github_trending", "github_trending_crawler",
            ScheduleUpdate(enabled=True), db=db, scheduler=scheduler,
        )
        assert resp.data["paused"] is False
    finally:
        scheduler.shutdown(wait=False)


async def test_update_schedule_reschedule_cron(db):
    scheduler = await _make_scheduler(db)
    try:
        before = scheduler.get_job("github_trending_crawler").next_run_time
        resp = await update_schedule(
            "github_trending", "github_trending_crawler",
            ScheduleUpdate(schedules=["0 23 * * *"]), db=db, scheduler=scheduler,
        )
        assert resp.success
        assert resp.data["schedules"] == ["0 23 * * *"]
    finally:
        scheduler.shutdown(wait=False)


async def test_update_schedule_invalid_cron_rejected(db):
    scheduler = await _make_scheduler(db)
    try:
        with pytest.raises(Exception):
            await update_schedule(
                "github_trending", "github_trending_crawler",
                ScheduleUpdate(schedules=["not a cron"]), db=db, scheduler=scheduler,
            )
    finally:
        scheduler.shutdown(wait=False)


async def test_update_schedule_empty_schedules_rejected(db):
    scheduler = await _make_scheduler(db)
    try:
        with pytest.raises(Exception):
            await update_schedule(
                "github_trending", "github_trending_crawler",
                ScheduleUpdate(schedules=[]), db=db, scheduler=scheduler,
            )
    finally:
        scheduler.shutdown(wait=False)
