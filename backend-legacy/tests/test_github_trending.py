# tests/test_github_trending.py
from datetime import datetime, timezone
from unittest.mock import patch

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.community.github_trending.crawler import GithubTrendingCrawler


def _repo(name, stars, period_stars, lang="python"):
    return {
        "fullname": f"author/{name}", "author": "author", "name": name,
        "url": f"https://github.com/author/{name}", "description": "d",
        "language": lang, "stars": stars, "forks": 1,
        "currentPeriodStars": period_stars,
    }


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    repos = [_repo("a", 100, 10), _repo("b", 50, 5)]
    with patch("app.community.github_trending.crawler.fetch_repos", return_value=repos):
        crawler = GithubTrendingCrawler(db, since="daily")
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2
    rows = await db.fetch(
        "SELECT key, response FROM crawl_data WHERE category='community' AND purpose='github'"
    )
    keys = {r["key"] for r in rows}
    assert keys == {"https://github.com/author/a", "https://github.com/author/b"}


@pytest.mark.asyncio
async def test_crawler_dedup_counts_new_only(db):
    await CrawlDataRepo(db).upsert(
        category="community", purpose="github", key="https://github.com/author/a",
        response={"name": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    repos = [_repo("a", 999, 1), _repo("b", 50, 5)]
    with patch("app.community.github_trending.crawler.fetch_repos", return_value=repos):
        crawler = GithubTrendingCrawler(db, since="daily")
        result = await crawler.run()

    assert result.items_new == 1


@pytest.mark.asyncio
async def test_router_period_sort_and_language(client, db):
    fetched = datetime.now(timezone.utc).isoformat()
    repo = CrawlDataRepo(db)

    def resp(cps, lang, period="daily"):
        return {"fullname": f"x/{cps}", "author": "x", "name": str(cps),
                "url": f"u{cps}", "description": None, "language": lang,
                "stars": 0, "forks": 0, "current_period_stars": cps,
                "period": period, "fetched_at": fetched}

    await repo.upsert(category="community", purpose="github", key="u1", response=resp(10, "python"), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="github", key="u2", response=resp(50, "python"), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="github", key="u3", response=resp(999, "rust"), date_at=datetime.now(timezone.utc))
    await repo.upsert(category="community", purpose="github", key="u4", response=resp(5, "python", "weekly"), date_at=datetime.now(timezone.utc))

    # daily 기본, current_period_stars DESC
    r = await client.get("/api/github-trending")
    body = r.json()
    assert r.status_code == 200
    assert len(body["data"]) == 3  # daily 3건 (weekly u4 제외)
    assert body["data"][0]["current_period_stars"] == 999

    # language 필터
    r = await client.get("/api/github-trending?language=python")
    body = r.json()
    assert len(body["data"]) == 2

    # period=weekly
    r = await client.get("/api/github-trending?period=weekly")
    body = r.json()
    assert len(body["data"]) == 1


@pytest.mark.asyncio
async def test_router_empty(client, db):
    r = await client.get("/api/github-trending")
    assert r.json()["data"] == []
