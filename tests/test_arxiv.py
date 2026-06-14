# tests/test_arxiv.py
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.feed.arxiv.crawler import ArxivCrawler


def _paper(pid, title, primary, published, categories=None):
    p = MagicMock()
    p.get_short_id.return_value = pid
    p.title = title
    p.summary = "summary"
    p.primary_category = primary
    p.categories = categories or [primary]
    p.pdf_url = f"https://arxiv.org/pdf/{pid}"
    p.entry_id = f"https://arxiv.org/abs/{pid}"
    p.published = published
    p.updated = published
    p.comment = None
    p.journal_ref = None
    author = MagicMock()
    author.name = "Author"
    p.authors = [author]
    return p


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    pub = datetime(2026, 6, 1, tzinfo=timezone.utc)
    papers = [_paper("2401.00001", "A", "cs.AI", pub), _paper("2401.00002", "B", "cs.LG", pub)]

    client = MagicMock()
    client.results.return_value = iter(papers)

    with patch("arxiv.Client", return_value=client):
        crawler = ArxivCrawler(db, categories=["cs.AI"])
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2

    rows = await db.fetch(
        "SELECT key FROM crawl_data WHERE category='feed' AND purpose='arxiv' ORDER BY key"
    )
    assert {r["key"] for r in rows} == {"2401.00001", "2401.00002"}


@pytest.mark.asyncio
async def test_crawler_dedup_counts_new_only(db):
    await CrawlDataRepo(db).upsert(
        category="feed", purpose="arxiv", key="2401.00001",
        response={"title": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    pub = datetime(2026, 6, 1, tzinfo=timezone.utc)
    papers = [_paper("2401.00001", "A-new", "cs.AI", pub), _paper("2401.00002", "B", "cs.AI", pub)]
    client = MagicMock()
    client.results.return_value = iter(papers)

    with patch("arxiv.Client", return_value=client):
        crawler = ArxivCrawler(db, categories=["cs.AI"])
        result = await crawler.run()

    assert result.items_new == 1


@pytest.mark.asyncio
async def test_router_filters_and_sort(client, db):
    fetched = datetime.now(timezone.utc).isoformat()
    pub_old = datetime(2026, 5, 1, tzinfo=timezone.utc)
    pub_new = datetime(2026, 6, 10, tzinfo=timezone.utc)
    repo = CrawlDataRepo(db)
    await repo.upsert(
        category="feed", purpose="arxiv", key="2401.00001",
        response={"arxiv_id": "2401.00001", "title": "Transformer A", "authors": [],
                  "summary": None, "primary_category": "cs.AI", "categories": ["cs.AI"],
                  "pdf_url": None, "abstract_url": None, "published_at": pub_old.isoformat(),
                  "updated_at": None, "author_comment": None, "journal_ref": None,
                  "fetched_at": fetched},
        date_at=pub_old,
    )
    await repo.upsert(
        category="feed", purpose="arxiv", key="2401.00002",
        response={"arxiv_id": "2401.00002", "title": "Diffusion B", "authors": [],
                  "summary": None, "primary_category": "cs.LG", "categories": ["cs.LG"],
                  "pdf_url": None, "abstract_url": None, "published_at": pub_new.isoformat(),
                  "updated_at": None, "author_comment": None, "journal_ref": None,
                  "fetched_at": fetched},
        date_at=pub_new,
    )

    # 전체 (published_at DESC)
    resp = await client.get("/api/arxiv")
    body = resp.json()
    assert resp.status_code == 200
    assert len(body["data"]) == 2
    assert body["data"][0]["arxiv_id"] == "2401.00002"  # 최신

    # category 필터
    resp = await client.get("/api/arxiv?category=cs.AI")
    assert len(resp.json()["data"]) == 1

    # query ILIKE
    resp = await client.get("/api/arxiv?query=transformer")
    body = resp.json()
    assert len(body["data"]) == 1
    assert body["data"][0]["title"] == "Transformer A"


@pytest.mark.asyncio
async def test_router_empty(client, db):
    resp = await client.get("/api/arxiv")
    assert resp.json()["data"] == []
