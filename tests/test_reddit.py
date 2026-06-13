# tests/test_reddit.py
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.community.reddit.crawler import RedditCrawler


def _post(pid, title, score, sub="programming", stickied=False):
    p = MagicMock()
    p.id = pid
    p.title = title
    p.author = "user"
    p.score = score
    p.upvote_ratio = 0.9
    p.num_comments = 5
    p.url = f"https://reddit.com/{pid}"
    p.permalink = f"/r/{sub}/comments/{pid}"
    p.selftext = ""
    p.is_self = False
    p.link_flair_text = None
    p.domain = "self"
    p.created_utc = 1748736000
    p.stickied = stickied
    return p


def _mock_reddit(posts):
    async def _aiter():
        for p in posts:
            yield p

    listing = MagicMock()
    listing.__aiter__ = lambda self: _aiter()

    sub = MagicMock()
    sub.hot = MagicMock(return_value=listing)

    reddit = MagicMock()
    reddit.subreddit = AsyncMock(return_value=sub)
    reddit.close = AsyncMock()
    return reddit


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    posts = [_post("a1", "A", 100), _post("b2", "B", 60)]
    with patch("asyncpraw.Reddit", return_value=_mock_reddit(posts)):
        crawler = RedditCrawler(db, client_id="x", client_secret="y", subreddits=["programming"], min_score=50)
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2
    rows = await db.fetch(
        "SELECT key FROM crawl_data WHERE category='community' AND purpose='reddit'"
    )
    assert {r["key"] for r in rows} == {"a1", "b2"}


@pytest.mark.asyncio
async def test_crawler_min_score_and_dedup(db):
    await CrawlDataRepo(db).upsert(
        category="community", purpose="reddit", key="a1",
        response={"title": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    posts = [_post("a1", "A-new", 80), _post("b2", "B", 70), _post("c3", "low", 10)]
    with patch("asyncpraw.Reddit", return_value=_mock_reddit(posts)):
        crawler = RedditCrawler(db, client_id="x", client_secret="y", subreddits=["programming"], min_score=50)
        result = await crawler.run()

    assert result.items_fetched == 2  # c3 min_score 미달
    assert result.items_new == 1


@pytest.mark.asyncio
async def test_router_sort_and_filters(client, db):
    fetched = datetime.now(timezone.utc).isoformat()
    repo = CrawlDataRepo(db)

    def resp(score, sub):
        return {"reddit_id": None, "title": f"t{score}", "author": None, "subreddit": sub,
                "score": score, "upvote_ratio": None, "num_comments": None, "url": None,
                "permalink": None, "selftext": None, "is_self": None, "link_flair": None,
                "domain": None, "posted_at": fetched, "fetched_at": fetched}

    await repo.upsert(category="community", purpose="reddit", key="1", response=resp(100, "programming"), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="reddit", key="2", response=resp(500, "programming"), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="reddit", key="3", response=resp(999, "python"), date_at=datetime.now(timezone.utc))

    # score DESC 전체
    resp1 = await client.get("/api/reddit")
    body = resp1.json()
    assert resp1.status_code == 200
    assert len(body["data"]) == 3
    assert body["data"][0]["score"] == 999

    # subreddit 필터
    resp2 = await client.get("/api/reddit?subreddit=programming")
    body = resp2.json()
    assert len(body["data"]) == 2
    assert body["data"][0]["score"] == 500

    # min_score 필터
    resp3 = await client.get("/api/reddit?min_score=200")
    assert len(resp3.json()["data"]) == 2


@pytest.mark.asyncio
async def test_router_empty(client, db):
    resp = await client.get("/api/reddit")
    assert resp.json()["data"] == []
