# tests/test_integration.py
import pytest


@pytest.mark.asyncio
async def test_full_news_flow(client, db):
    """Insert via DB, retrieve via API."""
    await db.execute(
        "INSERT INTO news_articles (source, title, url, fetched_at, category, importance) "
        "VALUES ($1, $2, $3, NOW(), $4, $5)",
        "test",
        "통합테스트 대통령 연설",
        "https://example.com/integration-1",
        "politics",
        "high",
    )
    # List
    resp = await client.get("/api/news")
    assert resp.json()["success"] is True
    assert len(resp.json()["data"]) >= 1

    # Single
    article = resp.json()["data"][0]
    resp2 = await client.get(f"/api/news/{article['id']}")
    assert resp2.json()["success"] is True
    assert resp2.json()["data"]["category"] == "politics"


@pytest.mark.asyncio
async def test_full_weather_flow(client, db):
    """Insert weather, retrieve via API."""
    await db.execute(
        "INSERT INTO weather_snapshots (location, fetched_at, temperature, humidity, condition) "
        "VALUES ($1, NOW(), $2, $3, $4)",
        "seoul",
        25.0,
        60,
        "맑음",
    )
    resp = await client.get("/api/weather")
    assert resp.json()["success"] is True
    assert resp.json()["data"]["temperature"] == 25.0

    # Crawl logs exist (from base_crawler tests or health check)
    resp2 = await client.get("/api/crawl-logs")
    assert resp2.json()["success"] is True


@pytest.mark.asyncio
async def test_health_with_data(client, db):
    await db.execute(
        "INSERT INTO news_articles (source, title, url, fetched_at, category, importance) "
        "VALUES ($1, $2, $3, NOW(), $4, $5)",
        "test",
        "Health test",
        "https://example.com/health-test",
        "tech",
        "low",
    )
    resp = await client.get("/api/health")
    body = resp.json()
    assert body["success"] is True
    assert body["data"]["total_records"]["news"] >= 1
