# tests/test_youtube_trending.py
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import pytest

from app.crawl_data.repo import CrawlDataRepo
from app.feed.youtube_trending.crawler import YoutubeTrendingCrawler


def _item(vid, title, views, region="KR", category_id="10"):
    return {
        "id": vid,
        "snippet": {
            "title": title,
            "channelTitle": "ch",
            "channelId": "cid",
            "description": "desc",
            "categoryId": category_id,
            "publishedAt": "2026-06-01T00:00:00Z",
            "thumbnails": {"high": {"url": f"https://img/{vid}"}},
        },
        "contentDetails": {"duration": "PT1M"},
        "statistics": {"viewCount": str(views), "likeCount": "1", "commentCount": "0"},
    }


def _mock_youtube(items):
    mock_yt = MagicMock()
    mock_req = MagicMock()
    mock_req.execute.return_value = {"items": items}
    mock_yt.videos.return_value.list.return_value = mock_req
    mock_yt.close = MagicMock()
    return mock_yt


@pytest.mark.asyncio
async def test_crawler_upserts_to_crawl_data(db):
    items = [_item("v1", "A", 100), _item("v2", "B", 50)]
    with patch("googleapiclient.discovery.build", return_value=_mock_youtube(items)):
        crawler = YoutubeTrendingCrawler(db, api_key="k", region_codes=["KR"])
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched == 2
    assert result.items_new == 2

    rows = await db.fetch(
        "SELECT key FROM crawl_data WHERE category='feed' AND purpose='youtube'"
    )
    assert {r["key"] for r in rows} == {"KR:v1", "KR:v2"}


@pytest.mark.asyncio
async def test_crawler_dedup_counts_new_only(db):
    await CrawlDataRepo(db).upsert(
        category="feed", purpose="youtube", key="KR:v1",
        response={"title": "old"}, date_at=datetime(2026, 6, 1, tzinfo=timezone.utc),
    )
    items = [_item("v1", "A-new", 999), _item("v2", "B", 50)]
    with patch("googleapiclient.discovery.build", return_value=_mock_youtube(items)):
        crawler = YoutubeTrendingCrawler(db, api_key="k", region_codes=["KR"])
        result = await crawler.run()

    assert result.items_new == 1


@pytest.mark.asyncio
async def test_router_region_sort_and_filter(client, db):
    fetched = datetime.now(timezone.utc).isoformat()
    repo = CrawlDataRepo(db)
    await repo.upsert(
        category="feed", purpose="youtube", key="KR:v1",
        response={"video_id": "v1", "title": "A", "channel_title": "", "channel_id": "",
                  "description": None, "category_id": "10", "published_at": fetched,
                  "view_count": 100, "like_count": 0, "comment_count": 0, "duration": None,
                  "thumbnail_url": None, "region_code": "KR", "fetched_at": fetched},
        date_at=datetime.now(timezone.utc),
    )
    await repo.upsert(
        category="feed", purpose="youtube", key="KR:v2",
        response={"video_id": "v2", "title": "B", "channel_title": "", "channel_id": "",
                  "description": None, "category_id": "20", "published_at": fetched,
                  "view_count": 500, "like_count": 0, "comment_count": 0, "duration": None,
                  "thumbnail_url": None, "region_code": "KR", "fetched_at": fetched},
        date_at=datetime.now(timezone.utc),
    )
    await repo.upsert(
        category="feed", purpose="youtube", key="US:v3",
        response={"video_id": "v3", "title": "US", "channel_title": "", "channel_id": "",
                  "description": None, "category_id": "10", "published_at": fetched,
                  "view_count": 9999, "like_count": 0, "comment_count": 0, "duration": None,
                  "thumbnail_url": None, "region_code": "US", "fetched_at": fetched},
        date_at=datetime.now(timezone.utc),
    )

    # KR region only, view_count DESC
    resp = await client.get("/api/youtube-trending?region_code=KR")
    body = resp.json()
    assert resp.status_code == 200
    assert len(body["data"]) == 2
    assert body["data"][0]["video_id"] == "v2"  # views 500

    # category_id 필터
    resp = await client.get("/api/youtube-trending?region_code=KR&category_id=10")
    body = resp.json()
    assert len(body["data"]) == 1
    assert body["data"][0]["video_id"] == "v1"


@pytest.mark.asyncio
async def test_router_empty(client, db):
    resp = await client.get("/api/youtube-trending?region_code=KR")
    assert resp.json()["data"] == []
