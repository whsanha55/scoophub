# tests/test_product_hunt.py
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.community.product_hunt.crawler import ProductHuntCrawler


def _node(pid, name, votes, topics):
    return {
        "id": pid, "name": name, "tagline": "tag", "slug": f"s{pid}",
        "url": f"https://producthunt.com/{pid}", "website": f"https://{pid}.com",
        "votesCount": votes, "commentsCount": 0, "featuredAt": "2026-06-01T00:00:00Z",
        "createdAt": "2026-06-01T00:00:00Z",
        "topics": {"edges": [{"node": {"name": t}} for t in topics]},
    }


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    data = {"data": {"posts": {"edges": [
        {"node": _node(1, "A", 100, ["AI", "SaaS"])},
        {"node": _node(2, "B", 50, ["Design"])},
    ]}}}
    resp = MagicMock()
    resp.raise_for_status = MagicMock()
    resp.json.return_value = data
    client = AsyncMock()
    client.post.return_value = resp
    client.__aenter__ = AsyncMock(return_value=client)
    client.__aexit__ = AsyncMock(return_value=False)

    with patch("app.community.product_hunt.crawler.httpx.AsyncClient", return_value=client):
        crawler = ProductHuntCrawler(db, developer_token="t")
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2
    rows = await db.fetch(
        "SELECT key FROM crawl_data WHERE category='community' AND purpose='producthunt'"
    )
    assert {r["key"] for r in rows} == {"1", "2"}


@pytest.mark.asyncio
async def test_crawler_dedup_counts_new_only(db):
    await CrawlDataRepo(db).upsert(
        category="community", purpose="producthunt", key="1",
        response={"name": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    data = {"data": {"posts": {"edges": [
        {"node": _node(1, "A-new", 999, ["AI"])},
        {"node": _node(2, "B", 50, ["AI"])},
    ]}}}
    resp = MagicMock()
    resp.raise_for_status = MagicMock()
    resp.json.return_value = data
    client = AsyncMock()
    client.post.return_value = resp
    client.__aenter__ = AsyncMock(return_value=client)
    client.__aexit__ = AsyncMock(return_value=False)

    with patch("app.community.product_hunt.crawler.httpx.AsyncClient", return_value=client):
        crawler = ProductHuntCrawler(db, developer_token="t")
        result = await crawler.run()

    assert result.items_new == 1


@pytest.mark.asyncio
async def test_router_sort_and_topic_filter(client, db):
    fetched = datetime.now(timezone.utc).isoformat()
    repo = CrawlDataRepo(db)

    def resp(votes, topics):
        return {"ph_id": None, "name": f"n{votes}", "tagline": None, "slug": None,
                "ph_url": None, "website_url": None, "votes_count": votes,
                "comments_count": 0, "topics": topics, "featured_at": None,
                "posted_at": fetched, "fetched_at": fetched}

    await repo.upsert(category="community", purpose="producthunt", key="1", response=resp(100, ["AI"]), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="producthunt", key="2", response=resp(500, ["AI", "SaaS"]), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="producthunt", key="3", response=resp(999, ["Design"]), date_at=datetime.now(timezone.utc))

    # votes DESC
    r = await client.get("/api/product-hunt")
    body = r.json()
    assert r.status_code == 200
    assert len(body["data"]) == 3
    assert body["data"][0]["votes_count"] == 999

    # topic 필터 (JSONB 포함)
    r = await client.get("/api/product-hunt?topic=AI")
    body = r.json()
    assert len(body["data"]) == 2


@pytest.mark.asyncio
async def test_router_empty(client, db):
    r = await client.get("/api/product-hunt")
    assert r.json()["data"] == []
