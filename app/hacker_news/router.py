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
        "## 필터\n"
        "- `item_type`: story / ask_hn / show_hn / job (기본: story)\n"
        "- `min_score`: 최소 점수 필터\n"
        "- `since`: ISO 8601 시간 필터 (예: 2026-06-06T00:00:00Z)\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- 점수 100+ 스토리: `?min_score=100`\n"
        "- Show HN 최근 1일: `?item_type=show_hn&since=2026-06-05T00:00:00Z`"
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
    description=(
        "Hacker News API(Algolia)에서 인기 스토리를 수집합니다.\n\n"
        "## 자동 스케줄\n"
        "- Cron: `0 */4 * * *` (KST, 4시간마다)\n"
        "- 설정: `config/settings.yaml` → `crawlers.hacker_news`\n\n"
        "## 수집 범위\n"
        "- story_types: top, best\n"
        "- max_items: 100\n"
        "- min_score: 50\n"
        "- dedup_window_hours: 24\n"
        "- source_timeout_seconds: 30\n\n"
        "## 수동 실행\n"
        "스케줄과 무관하게 즉시 크롤을 트리거합니다.\n\n"
        "## 응답\n"
        "- `items_fetched`: 수집된 전체 아이템 수\n"
        "- `items_new`: 신규 저장 아이템 수\n"
        "- `errors`: 오류 목록 (없으면 null)"
    ),
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
