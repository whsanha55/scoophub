# tests/test_devto_hashnode.py
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.feed.devto_hashnode.crawler import DevtoHashnodeCrawler


def _article(aid, title, tags, reactions, published_at):
    return {
        "id": aid,
        "title": title,
        "url": f"https://dev.to/a/{aid}",
        "user": {"name": "author"},
        "description": "desc",
        "public_reactions_count": reactions,
        "comments_count": 0,
        "reading_time": 5,
        "tag_list": tags,
        "published_at": published_at,
    }


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    now_iso = datetime.now(timezone.utc).isoformat()
    articles = [
        _article(1, "A", ["python", "webdev"], 10, now_iso),
        _article(2, "B", ["javascript"], 5, now_iso),
    ]

    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = articles
    mock_client = AsyncMock()
    mock_client.get.return_value = mock_response
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)

    with patch("app.feed.devto_hashnode.crawler.httpx.AsyncClient", return_value=mock_client):
        crawler = DevtoHashnodeCrawler(db, tags=["python"])
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2

    rows = await db.fetch(
        "SELECT key FROM crawl_data WHERE category='feed' AND purpose='devblog' ORDER BY key"
    )
    assert {r["key"] for r in rows} == {"1", "2"}


@pytest.mark.asyncio
async def test_crawler_dedup_counts_new_only(db):
    await CrawlDataRepo(db).upsert(
        category="feed", purpose="devblog", key="1",
        response={"title": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    now_iso = datetime.now(timezone.utc).isoformat()
    articles = [
        _article(1, "A-new", ["python"], 99, now_iso),
        _article(2, "B", ["python"], 5, now_iso),
    ]
    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json.return_value = articles
    mock_client = AsyncMock()
    mock_client.get.return_value = mock_response
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)

    with patch("app.feed.devto_hashnode.crawler.httpx.AsyncClient", return_value=mock_client):
        crawler = DevtoHashnodeCrawler(db, tags=["python"])
        result = await crawler.run()

    assert result.items_new == 1


@pytest.mark.asyncio
async def test_router_latest_batch_tag_and_sort(client, db):
    fetched = datetime.now(timezone.utc).isoformat()
    repo = CrawlDataRepo(db)
    await repo.upsert(
        category="feed", purpose="devblog", key="1",
        response={"article_id": 1, "title": "A", "url": "u1", "author": None,
                  "description": None, "reactions_count": 5, "comments_count": 0,
                  "reading_time": None, "tags": ["python"], "source": "devto",
                  "published_at": fetched, "fetched_at": fetched},
        date_at=datetime.now(timezone.utc),
    )
    await repo.upsert(
        category="feed", purpose="devblog", key="2",
        response={"article_id": 2, "title": "B", "url": "u2", "author": None,
                  "description": None, "reactions_count": 50, "comments_count": 0,
                  "reading_time": None, "tags": ["javascript"], "source": "devto",
                  "published_at": fetched, "fetched_at": fetched},
        date_at=datetime.now(timezone.utc),
    )

    # reactions_count DESC 정렬
    resp = await client.get("/api/devto-hashnode")
    body = resp.json()
    assert resp.status_code == 200
    assert len(body["data"]) == 2
    assert body["data"][0]["title"] == "B"  # reactions 50 > 5

    # tag 필터
    resp = await client.get("/api/devto-hashnode?tag=python")
    body = resp.json()
    assert len(body["data"]) == 1
    assert body["data"][0]["title"] == "A"


@pytest.mark.asyncio
async def test_router_empty(client, db):
    resp = await client.get("/api/devto-hashnode")
    body = resp.json()
    assert resp.status_code == 200
    assert body["data"] == []
