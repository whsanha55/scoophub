# tests/test_api_weather.py
from datetime import datetime, timezone

import pytest

from app.crawl_data.repo import CrawlDataRepo


def _snapshot(repo, location="seoul", temperature=22.5, date_at=None, weekly=None):
    resp = {
        "location": location,
        "fetched_at": (date_at or datetime.now(timezone.utc)).isoformat(),
        "temperature": temperature,
        "humidity": 55,
        "weekly_forecast": weekly or [],
    }
    return repo.upsert(
        category="weather", purpose="snapshot", key=location,
        response=resp, date_at=date_at or datetime.now(timezone.utc),
    )


@pytest.mark.asyncio
async def test_get_weather_empty(client, db):
    response = await client.get("/api/weather")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"] is None


@pytest.mark.asyncio
async def test_get_weather_latest(client, db):
    await _snapshot(CrawlDataRepo(db), temperature=22.5)
    response = await client.get("/api/weather")
    body = response.json()
    assert body["success"] is True
    assert body["data"]["temperature"] == 22.5


@pytest.mark.asyncio
async def test_get_weather_stale_returns_null(client, db):
    # 60분 전 스냅샷 → 기본 30분 창에 없음 → null
    old = datetime.now(timezone.utc) - __import__("datetime").timedelta(minutes=60)
    await _snapshot(CrawlDataRepo(db), temperature=18.0, date_at=old)
    response = await client.get("/api/weather?minutes=30")
    body = response.json()
    assert body["success"] is True
    assert body["data"] is None


@pytest.mark.asyncio
async def test_get_weather_forecast(client, db):
    weekly = [
        {"date": "2026-06-12", "maxtempC": "25"},
        {"date": "2026-06-13", "maxtempC": "24"},
    ]
    await _snapshot(CrawlDataRepo(db), weekly=weekly)
    response = await client.get("/api/weather/forecast?limit=1")
    body = response.json()
    assert response.status_code == 200
    assert len(body["data"]) == 1
    assert body["data"][0]["date"] == "2026-06-12"
