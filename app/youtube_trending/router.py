# youtube_trending/router.py
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, Query

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


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

    # 최신 fetched_at 기준으로 필터링
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM youtube_trending WHERE region_code = $1",
        region_code,
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions = ["fetched_at = $1", "region_code = $2"]
    params: list = [latest["latest"], region_code]
    idx = 3

    if category_id is not None:
        conditions.append(f"category_id = ${idx}")
        params.append(category_id)
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM youtube_trending WHERE {where} "
        f"ORDER BY view_count DESC NULLS LAST LIMIT ${idx}",
        *params,
        limit,
    )

    items = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=items, meta={"total": len(items), "returned": len(items)})


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


# ────────────────────────────────────────────────────────────
#  수동 크롤 트리거
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/youtube-trending",
    summary="YouTube 트렌딩 크롤 수동 실행",
    description=(
        "YouTube Data API에서 트렌딩 영상을 수집합니다.\n\n"
        "## 자동 스케줄\n"
        "- Cron: `0 */6 * * *` (KST, 6시간마다)\n"
        "- 설정: `config/settings.yaml` → `crawlers.youtube_trending`\n\n"
        "## 수집 범위\n"
        "- region_codes: KR, US\n"
        "- max_results_per_region: 50\n"
        "- source_timeout_seconds: 15\n"
        "- retry_count: 3\n\n"
        "## 수동 실행\n"
        "스케줄과 무관하게 즉시 크롤을 트리거합니다.\n\n"
        "## 응답\n"
        "- `items_fetched`: 수집된 전체 아이템 수\n"
        "- `items_new`: 신규 저장 아이템 수\n"
        "- `errors`: 오류 목록 (없으면 null)"
    ),
    tags=["YouTube Trending Crawling"],
)
async def crawling_youtube_trending(db: Database = Depends(_get_db)):
    logger.info("manual youtube_trending crawl triggered")
    import yaml

    from app.youtube_trending.crawler import YoutubeTrendingCrawler

    with open("config/settings.yaml") as f:
        cfg = yaml.safe_load(f)
    yt_cfg = cfg["crawlers"]["youtube_trending"]

    result = await YoutubeTrendingCrawler(
        db,
        api_key=yt_cfg.get("api_key", ""),
        region_codes=yt_cfg.get("region_codes"),
        max_results_per_region=yt_cfg.get("max_results_per_region", 50),
    ).run()

    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="YouTube 트렌딩 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "youtube_trending",
        "crawler_detail": "most_popular",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
