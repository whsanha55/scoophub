# youtube_trending/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class YoutubeTrendingRouter(BaseRouter):
    table_name = "youtube_trending"
    route_path = "/youtube-trending"
    crawler_import = "app.youtube_trending.crawler"
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

    # 최신 fetched_at 기준으로 필터링 (region_code 조건 포함)
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM youtube_trending WHERE region_code = $1",
        region_code,
    )
    if not latest or not latest["latest"]:
        return _base.empty_response()

    conditions = ["fetched_at = $1", "region_code = $2"]
    params: list = [latest["latest"], region_code]
    idx = 3

    if category_id is not None:
        conditions.append(f"category_id = ${idx}")
        params.append(category_id)
        idx += 1

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
