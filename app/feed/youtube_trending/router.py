# youtube_trending/router.py
from __future__ import annotations

import json
import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class YoutubeTrendingRouter(BaseRouter):
    table_name = "feed_youtube"
    route_path = "/youtube-trending"
    crawler_import = "app.feed.youtube_trending.crawler"
    crawler_class_name = "YoutubeTrendingCrawler"
    api_tag = "YouTube Trending"
    order_by = "view_count DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = YoutubeTrendingRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/youtube-trending",
    tags=["YouTube Trending"],
    summary="YouTube 트렌딩 영상 조회",
    description=(
        "최신 YouTube 트렌딩 영상 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `region_code`: 국가 코드 (기본: KR, 예: KR, US)\n"
        "- `category_id`: 카테고리 ID 필터\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- 한국 트렌딩: `?region_code=KR`\n"
        "- 미국 트렌딩 상위 50: `?region_code=US&limit=50`"
    ),
)
async def get_youtube_trending(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    region_code: str = Query("KR", description="국가 코드"),
    category_id: str | None = Query(None, description="카테고리 ID"),
    db: Database = Depends(_get_db),
):
    logger.info("get_youtube_trending requested: region=%s category=%s limit=%d", region_code, category_id, limit)

    # feed_youtube → crawl_data(category=feed, purpose=youtube).
    # region별 최신 배치 = response.fetched_at의 MAX (해당 region).
    latest = await db.fetchval(
        "SELECT MAX((response->>'fetched_at')::timestamptz) FROM crawl_data "
        "WHERE category='feed' AND purpose='youtube' AND response->>'region_code'=$1",
        region_code,
    )
    if not latest:
        return _base.empty_response()

    conditions = ["(response->>'fetched_at')::timestamptz = $1", "response->>'region_code' = $2"]
    params: list = [latest, region_code]
    idx = 3

    if category_id is not None:
        conditions.append(f"response->>'category_id' = ${idx}")
        params.append(category_id)
        idx += 1

    where = " AND ".join(conditions)
    rows = await db.fetch(
        f"SELECT id, key, date_at, response "
        f"FROM crawl_data "
        f"WHERE category='feed' AND purpose='youtube' AND {where} "
        f"ORDER BY (response->>'view_count')::bigint DESC NULLS LAST LIMIT ${idx}",
        *params, limit,
    )
    items = [_youtube_item(r) for r in rows]
    return _base.items_response(items)


def _youtube_item(row) -> dict:
    """crawl_data row → 기존 feed_youtube 응답 필드로 재구성."""
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "id": row["id"],
        "video_id": resp.get("video_id"),
        "title": resp.get("title"),
        "channel_title": resp.get("channel_title"),
        "channel_id": resp.get("channel_id"),
        "description": resp.get("description"),
        "category_id": resp.get("category_id"),
        "published_at": resp.get("published_at"),
        "view_count": resp.get("view_count", 0),
        "like_count": resp.get("like_count", 0),
        "comment_count": resp.get("comment_count", 0),
        "duration": resp.get("duration"),
        "thumbnail_url": resp.get("thumbnail_url"),
        "region_code": resp.get("region_code"),
        "fetched_at": resp.get("fetched_at"),
    }
