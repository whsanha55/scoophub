# hacker_news/router.py
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
    "/hacker-news",
    tags=["Hacker News"],
    summary="Hacker News 아이템 조회",
    description=(
        "최신 Hacker News 아이템 목록을 반환합니다.\n\n"
        "- `item_type`: story / ask_hn / show_hn / job\n"
        "- `min_score`: 최소 점수 필터\n"
        "- `since`: ISO 8601 시간 필터 (예: 2026-06-06T00:00:00Z)\n"
        "- `limit`: 최대 반환 개수"
    ),
)
async def get_hacker_news(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    min_score: int | None = Query(None, description="최소 점수 필터"),
    item_type: str = Query("story", description="아이템 타입: story / ask_hn / show_hn / job"),
    since: str | None = Query(None, description="ISO 8601 시간 필터"),
    db: Database = Depends(_get_db),
):
    logger.info("get_hacker_news requested: limit=%d min_score=%s item_type=%s since=%s", limit, min_score, item_type, since)
    conditions: list[str] = []
    params: list = []
    idx = 1

    # 최신 fetched_at 기준으로 필터링
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM hacker_news"
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions.append(f"fetched_at = ${idx}")
    params.append(latest["latest"])
    idx += 1

    if item_type is not None:
        conditions.append(f"item_type = ${idx}")
        params.append(item_type)
        idx += 1

    if min_score is not None:
        conditions.append(f"score >= ${idx}")
        params.append(min_score)
        idx += 1

    if since is not None:
        conditions.append(f"posted_at >= ${idx}")
        params.append(since)
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM hacker_news WHERE {where} "
        f"ORDER BY score DESC NULLS LAST LIMIT ${idx}",
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
    "/crawling/hacker-news",
    summary="Hacker News 크롤 수동 실행",
    description="Hacker News API로 아이템을 수집합니다.",
    tags=["Hacker News Crawling"],
)
async def crawling_hacker_news(db: Database = Depends(_get_db)):
    logger.info("manual hacker_news crawl triggered")
    from app.hacker_news.crawler import HackerNewsCrawler

    result = await HackerNewsCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="Hacker News 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "hacker_news",
        "crawler_detail": "top_stories",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
