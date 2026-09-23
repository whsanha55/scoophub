# tests/test_crawl_data.py
import json
from datetime import datetime, timezone

import pytest

from app.crawl_data.repo import CrawlDataRepo, upsert_crawl_data


@pytest.fixture(autouse=True)
async def _clean(db):
    pool = await db.pool
    async with pool.acquire() as conn:
        await conn.execute("TRUNCATE crawl_data RESTART IDENTITY CASCADE")
    yield
    async with pool.acquire() as conn:
        await conn.execute("TRUNCATE crawl_data RESTART IDENTITY CASCADE")


async def test_upsert_inserts_new_row(db):
    repo = CrawlDataRepo(db)
    row_id = await repo.upsert(
        category="kal",
        purpose="bonus_seat",
        key="ICN-LHR-202701",
        response={"flightList": [{"departureDate": "20270101"}]},
    )
    assert isinstance(row_id, int)

    row = await repo.get(category="kal", purpose="bonus_seat", key="ICN-LHR-202701")
    assert row is not None
    assert row["response"]["flightList"][0]["departureDate"] == "20270101"


async def test_upsert_updates_existing_key(db):
    repo = CrawlDataRepo(db)
    first = await repo.upsert(
        category="kal",
        purpose="bonus_seat",
        key="ICN-LHR-202701",
        response={"v": 1},
    )
    later = datetime(2027, 1, 2, 9, 0, tzinfo=timezone.utc)
    updated = await repo.upsert(
        category="kal",
        purpose="bonus_seat",
        key="ICN-LHR-202701",
        response={"v": 2},
        date_at=later,
    )

    # 동일 key → 같은 row id, 최신 응답으로 덮어씀
    assert updated == first
    row = await repo.get(category="kal", purpose="bonus_seat", key="ICN-LHR-202701")
    assert row["response"] == {"v": 2}
    assert row["date_at"] == later


async def test_latest_returns_most_recent(db):
    repo = CrawlDataRepo(db)
    await repo.upsert(
        category="kal", purpose="bonus_seat", key="ICN-LHR-202701",
        response={"k": "old"}, date_at=datetime(2027, 1, 1, tzinfo=timezone.utc),
    )
    await repo.upsert(
        category="kal", purpose="bonus_seat", key="ICN-FRA-202701",
        response={"k": "new"}, date_at=datetime(2027, 1, 5, tzinfo=timezone.utc),
    )
    latest = await repo.latest(category="kal", purpose="bonus_seat")
    assert latest is not None
    assert latest["key"] == "ICN-FRA-202701"
    assert latest["response"]["k"] == "new"


async def test_query_path_filters_jsonb(db):
    repo = CrawlDataRepo(db)
    await repo.upsert(
        category="kal", purpose="bonus_seat", key="ICN-LHR-202701",
        response={"meta": {"status": "open"}}, date_at=datetime(2027, 1, 1, tzinfo=timezone.utc),
    )
    await repo.upsert(
        category="kal", purpose="bonus_seat", key="ICN-CDG-202701",
        response={"meta": {"status": "closed"}}, date_at=datetime(2027, 1, 2, tzinfo=timezone.utc),
    )
    hits = await repo.query_path(
        category="kal", purpose="bonus_seat", path="meta.status", value="open"
    )
    assert len(hits) == 1
    assert hits[0]["key"] == "ICN-LHR-202701"


async def test_function_helper(db):
    row_id = await upsert_crawl_data(
        db, category="crawl_run", purpose="smoke", key="run-1",
        response={"ok": True},
    )
    repo = CrawlDataRepo(db)
    row = await repo.get(category="crawl_run", purpose="smoke", key="run-1")
    assert row["response"] == {"ok": True}
    assert row["id"] == row_id
