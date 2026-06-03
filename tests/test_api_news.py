# tests/test_api_news.py
import pytest
from datetime import datetime, timezone, timedelta


@pytest.mark.asyncio
async def test_get_news_empty(client):
    response = await client.get("/api/news")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert isinstance(body["data"], list)


@pytest.mark.asyncio
async def test_get_news_with_data(client, db):
    await db.execute(
        "INSERT INTO news_articles (source, title, url, category, importance) "
        "VALUES ($1, $2, $3, $4, $5)",
        "test",
        "Test Article",
        "https://example.com/test-api-1",
        "politics",
        4,
    )
    response = await client.get("/api/news")
    body = response.json()
    assert body["success"] is True
    assert len(body["data"]) >= 1
    assert body["data"][0]["title"] == "Test Article"


@pytest.mark.asyncio
async def test_get_news_by_minutes(client, db):
    await db.execute(
        "INSERT INTO news_articles (source, title, url, created_at, category, importance) "
        "VALUES ($1, $2, $3, NOW() - interval '60 minutes', $4, $5)",
        "test",
        "Old Article",
        "https://example.com/old",
        "economy",
        2,
    )
    await db.execute(
        "INSERT INTO news_articles (source, title, url, created_at, category, importance) "
        "VALUES ($1, $2, $3, NOW(), $4, $5)",
        "test",
        "Recent Article",
        "https://example.com/recent",
        "tech",
        3,
    )
    response = await client.get("/api/news?minutes=30")
    body = response.json()
    assert body["success"] is True
    titles = [a["title"] for a in body["data"]]
    assert "Recent Article" in titles
    assert "Old Article" not in titles


@pytest.mark.asyncio
async def test_get_news_by_id(client, db):
    row = await db.fetchrow(
        "INSERT INTO news_articles (source, title, url, category, importance) "
        "VALUES ($1, $2, $3, $4, $5) RETURNING id",
        "test",
        "Single Article",
        "https://example.com/single",
        "politics",
        3,
    )
    article_id = row["id"]
    response = await client.get(f"/api/news/{article_id}")
    body = response.json()
    assert body["success"] is True
    assert body["data"]["title"] == "Single Article"


@pytest.mark.asyncio
async def test_get_news_by_id_not_found(client):
    response = await client.get("/api/news/99999")
    assert response.status_code == 404
    body = response.json()
    assert body["success"] is False
