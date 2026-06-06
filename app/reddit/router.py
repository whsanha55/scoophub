# reddit/router.py
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
    "/reddit",
    tags=["Reddit"],
    summary="Reddit 포스트 조회",
    description=(
        "최신 Reddit 포스트 목록을 반환합니다.\n\n"
        "- `subreddit`: 서브레딧 필터\n"
        "- `min_score`: 최소 점수 필터\n"
        "- `since`: ISO 8601 기준 posted_at 필터\n"
        "- `limit`: 최대 반환 개수"
    ),
)
async def get_reddit(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    subreddit: str | None = Query(None, description="서브레딧 필터"),
    min_score: int | None = Query(None, description="최소 점수 필터"),
    since: str | None = Query(None, description="posted_at 기준 ISO 8601 필터"),
    db: Database = Depends(_get_db),
):
    logger.info("get_reddit requested: limit=%d subreddit=%s min_score=%s since=%s", limit, subreddit, min_score, since)
    conditions: list[str] = []
    params: list = []
    idx = 1

    # 최신 fetched_at 기준으로 필터링
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM reddit_posts",
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions.append(f"fetched_at = ${idx}")
    params.append(latest["latest"])
    idx += 1

    if subreddit is not None:
        conditions.append(f"subreddit = ${idx}")
        params.append(subreddit)
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
        f"SELECT * FROM reddit_posts WHERE {where} "
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
    "/crawling/reddit",
    summary="Reddit 크롤 수동 실행",
    description="Reddit API로 포스트를 수집합니다.",
    tags=["Reddit Crawling"],
)
async def crawling_reddit(db: Database = Depends(_get_db)):
    logger.info("manual reddit crawl triggered")
    from app.reddit.crawler import RedditCrawler

    result = await RedditCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="Reddit 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "reddit",
        "crawler_detail": "hot_posts",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
