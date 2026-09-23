# tests/test_tech_newsletter.py
from datetime import datetime, timezone
from unittest.mock import patch, MagicMock

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.feed.tech_newsletter.crawler import TechNewsletterCrawler


def _entry(title, url, source, published_parsed, category=None):
    return {
        "title": title,
        "link": url,
        "source": source,
        "summary": f"{title} summary",
        "author": "author",
        "category": category,
        "published_parsed": published_parsed,
    }


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    pp = (2026, 6, 1, 9, 0, 0)
    entries = [
        _entry("A", "https://ex.com/a", "TLDR Tech", pp, "ai"),
        _entry("B", "https://ex.com/b", "TechCrunch", pp),
    ]
    feeds = [{"url": "https://tldr.tech/api/rss/tech", "source": "TLDR Tech"}]

    mock_feed = MagicMock()
    mock_feed.entries = entries

    with patch("app.feed.tech_newsletter.crawler.feedparser.parse", return_value=mock_feed):
        crawler = TechNewsletterCrawler(db, feeds=feeds)
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2

    rows = await db.fetch(
        "SELECT key, response FROM crawl_data "
        "WHERE category='feed' AND purpose='newsletter' ORDER BY key"
    )
    assert len(rows) == 2
    assert rows[0]["key"] == "https://ex.com/a"


@pytest.mark.asyncio
async def test_crawler_dedup_counts_new_only(db):
    pp = (2026, 6, 1, 9, 0, 0)
    # 사전에 동일 url 1건 존재
    await CrawlDataRepo(db).upsert(
        category="feed", purpose="newsletter", key="https://ex.com/a",
        response={"title": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    entries = [
        _entry("A-new", "https://ex.com/a", "TLDR Tech", pp),
        _entry("B", "https://ex.com/b", "TLDR Tech", pp),
    ]
    feeds = [{"url": "x", "source": "TLDR Tech"}]
    mock_feed = MagicMock()
    mock_feed.entries = entries

    with patch("app.feed.tech_newsletter.crawler.feedparser.parse", return_value=mock_feed):
        crawler = TechNewsletterCrawler(db, feeds=feeds)
        result = await crawler.run()

    assert result.items_new == 1  # B만 신규


@pytest.mark.asyncio
async def test_router_latest_batch_and_source_filter(client, db):
    now = datetime.now(timezone.utc)
    fetched = now.isoformat()
    repo = CrawlDataRepo(db)
    await repo.upsert(
        category="feed", purpose="newsletter", key="https://ex.com/a",
        response={"title": "A", "source": "TLDR Tech", "summary": None,
                  "author": None, "category": None, "published_at": now.isoformat(),
                  "fetched_at": fetched},
        date_at=now,
    )
    await repo.upsert(
        category="feed", purpose="newsletter", key="https://ex.com/b",
        response={"title": "B", "source": "TechCrunch", "summary": None,
                  "author": None, "category": None, "published_at": now.isoformat(),
                  "fetched_at": fetched},
        date_at=now,
    )

    # 전체 최신 배치
    resp = await client.get("/api/tech-newsletter")
    body = resp.json()
    assert resp.status_code == 200
    assert body["success"] is True
    assert len(body["data"]) == 2

    # source 필터
    resp = await client.get("/api/tech-newsletter?source=TechCrunch")
    body = resp.json()
    assert len(body["data"]) == 1
    assert body["data"][0]["source"] == "TechCrunch"
    assert body["data"][0]["url"] == "https://ex.com/b"


@pytest.mark.asyncio
async def test_router_empty(client, db):
    resp = await client.get("/api/tech-newsletter")
    body = resp.json()
    assert resp.status_code == 200
    assert body["data"] == []
    assert body["meta"]["total"] == 0


@pytest.mark.asyncio
async def test_router_since_filter(client, db):
    repo = CrawlDataRepo(db)
    old = datetime(2026, 5, 1, tzinfo=timezone.utc)
    new = datetime(2026, 6, 10, tzinfo=timezone.utc)
    await repo.upsert(
        category="feed", purpose="newsletter", key="https://ex.com/old",
        response={"title": "old", "source": "S", "summary": None,
                  "author": None, "category": None, "published_at": old.isoformat(),
                  "fetched_at": new.isoformat()},
        date_at=old,
    )
    await repo.upsert(
        category="feed", purpose="newsletter", key="https://ex.com/new",
        response={"title": "new", "source": "S", "summary": None,
                  "author": None, "category": None, "published_at": new.isoformat(),
                  "fetched_at": new.isoformat()},
        date_at=new,
    )

    resp = await client.get("/api/tech-newsletter?since=2026-06-01T00:00:00Z")
    body = resp.json()
    assert len(body["data"]) == 1
    assert body["data"][0]["url"] == "https://ex.com/new"
