# tests/test_hacker_news.py
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.community.hacker_news.crawler import HackerNewsCrawler


def _hn(iid, title, score, itype="story", descendants=0):
    return {
        "id": iid, "title": title, "url": f"https://news.ycombinator.com/item?id={iid}",
        "by": "user", "score": score, "descendants": descendants, "type": itype,
        "text": None, "time": 1748736000,
    }


def _mock_client(story_ids, items):
    """httpx AsyncClient mock: topstories/beststories + /item/{id}."""
    item_map = {i["id"]: i for i in items}

    def get(path):
        resp = MagicMock()
        resp.raise_for_status = MagicMock()
        if path.endswith("stories.json"):
            resp.json.return_value = story_ids
        else:
            iid = int(path.split("/item/")[1].split(".")[0])
            resp.json.return_value = item_map.get(iid)
        return resp

    client = AsyncMock()
    client.get = AsyncMock(side_effect=get)
    client.__aenter__ = AsyncMock(return_value=client)
    client.__aexit__ = AsyncMock(return_value=False)
    return client


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    items = [_hn(1, "A", 100), _hn(2, "B", 60)]
    client = _mock_client([1, 2], items)
    with patch("app.community.hacker_news.crawler.httpx.AsyncClient", return_value=client):
        crawler = HackerNewsCrawler(db, min_score=50, story_types=["top"])
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2
    rows = await db.fetch(
        "SELECT key FROM crawl_data WHERE category='community' AND purpose='hackernews'"
    )
    assert {r["key"] for r in rows} == {"1", "2"}


@pytest.mark.asyncio
async def test_crawler_min_score_filter_and_dedup(db):
    await CrawlDataRepo(db).upsert(
        category="community", purpose="hackernews", key="1",
        response={"title": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    # 3은 min_score 미달(50) → 제외, 2는 신규, 1은 기존
    items = [_hn(1, "A-new", 80), _hn(2, "B", 70), _hn(3, "low", 10)]
    client = _mock_client([1, 2, 3], items)
    with patch("app.community.hacker_news.crawler.httpx.AsyncClient", return_value=client):
        crawler = HackerNewsCrawler(db, min_score=50, story_types=["top"])
        result = await crawler.run()

    assert result.items_fetched == 2  # 3 제외
    assert result.items_new == 1  # 2만 신규


@pytest.mark.asyncio
async def test_router_sort_and_filters(client, db):
    fetched = datetime.now(timezone.utc).isoformat()
    repo = CrawlDataRepo(db)
    base_resp = lambda score, itype: {
        "hn_id": None, "title": f"t{score}", "url": None, "by_user": None,
        "score": score, "descendants": None, "item_type": itype, "body_text": None,
        "posted_at": fetched, "fetched_at": fetched}
    await repo.upsert(category="community", purpose="hackernews", key="1",
                      response=base_resp(100, "story"), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="hackernews", key="2",
                      response=base_resp(500, "story"), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="hackernews", key="3",
                      response=base_resp(30, "job"), date_at=datetime.now(timezone.utc))

    # score DESC (기본 item_type=story → job 1건 제외, story 2건)
    resp = await client.get("/api/hacker-news")
    body = resp.json()
    assert resp.status_code == 200
    assert len(body["data"]) == 2
    assert body["data"][0]["score"] == 500

    # item_type 필터 (job)
    resp = await client.get("/api/hacker-news?item_type=job")
    body = resp.json()
    assert len(body["data"]) == 1
    assert body["data"][0]["item_type"] == "job"

    # min_score 필터 (story 중 100 이상 = 2건)
    resp = await client.get("/api/hacker-news?min_score=100")
    body = resp.json()
    assert len(body["data"]) == 2


@pytest.mark.asyncio
async def test_router_empty(client, db):
    resp = await client.get("/api/hacker-news")
    assert resp.json()["data"] == []
