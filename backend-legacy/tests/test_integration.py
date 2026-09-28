# tests/test_integration.py
import pytest


@pytest.mark.asyncio
async def test_full_news_flow(client, db):
    """Insert via DB, retrieve via API."""
    await db.execute(
        "INSERT INTO feed_news (source, title, url, category, importance) "
        "VALUES ($1, $2, $3, $4, $5)",
        "test",
        "통합테스트 대통령 연설",
        "https://example.com/integration-1",
        "politics",
        4,
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
    from datetime import datetime, timezone
    from app.crawl_data.repo import CrawlDataRepo

    await CrawlDataRepo(db).upsert(
        category="weather", purpose="snapshot", key="seoul",
        response={"location": "seoul", "fetched_at": datetime.now(timezone.utc).isoformat(),
                  "temperature": 25.0, "humidity": 60, "condition": "맑음",
                  "weekly_forecast": []},
        date_at=datetime.now(timezone.utc),
    )
    resp = await client.get("/api/weather")
    assert resp.json()["success"] is True
    assert resp.json()["data"]["temperature"] == 25.0

    # Crawl logs exist (from base_crawler tests or health check)
    resp2 = await client.get("/api/crawl-logs")
    assert resp2.json()["success"] is True


@pytest.mark.asyncio
async def test_health_no_db_dependency(client):
    """헬스체크는 DB 조회 없이 서버 통신만 확인한다 (이슈 #141)."""
    resp = await client.get("/api/health")
    body = resp.json()
    assert resp.status_code == 200
    assert body["success"] is True
    assert body["data"]["status"] == "ok"
    assert "total_records" not in body["data"]
