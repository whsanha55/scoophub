# devto_hashnode/router.py
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
    "/devto-hashnode",
    tags=["Dev.to"],
    summary="Dev.to 트렌딩 아티클 조회",
    description=(
        "최신 Dev.to 트렌딩 아티클 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `tag`: 태그 필터 (예: python, javascript, webdev)\n"
        "- `since`: published_at 기준 ISO 8601 필터\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- Python 아티클: `?tag=python`\n"
        "- 최근 1일 WebDev: `?tag=webdev&since=2026-06-05T00:00:00Z`"
    ),
)
async def get_devto_hashnode(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    tag: str | None = Query(None, description="태그 필터"),
    since: str | None = Query(None, description="published_at 기준 ISO 8601 필터"),
    db: Database = Depends(_get_db),
):
    logger.info("get_devto_hashnode requested: limit=%d tag=%s since=%s", limit, tag, since)

    # 최신 fetched_at 기준 필터링
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM devto_hashnode",
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions = ["fetched_at = $1"]
    params: list = [latest["latest"]]
    idx = 2

    if tag is not None:
        conditions.append(f"tags @> ${idx}::jsonb")
        params.append(f'["{tag}"]')
        idx += 1

    if since is not None:
        conditions.append(f"published_at >= ${idx}")
        params.append(since)
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM devto_hashnode WHERE {where} "
        f"ORDER BY reactions_count DESC NULLS LAST LIMIT ${idx}",
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
    "/crawling/devto-hashnode",
    summary="Dev.to 크롤 수동 실행",
    description=(
        "Dev.to API에서 태그별 트렌딩 아티클을 수집합니다.\n\n"
        "## 자동 스케줄\n"
        "- Cron: `0 */4 * * *` (KST, 4시간마다)\n"
        "- 설정: `config/settings.yaml` → `crawlers.devto_hashnode`\n\n"
        "## 수집 범위\n"
        "- tags: python, javascript, webdev, tutorial, beginners\n"
        "- max_articles_per_tag: 30\n"
        "- source_timeout_seconds: 10\n"
        "- retry_count: 3\n\n"
        "## 수동 실행\n"
        "스케줄과 무관하게 즉시 크롤을 트리거합니다.\n\n"
        "## 응답\n"
        "- `items_fetched`: 수집된 전체 아이템 수\n"
        "- `items_new`: 신규 저장 아이템 수\n"
        "- `errors`: 오류 목록 (없으면 null)"
    ),
    tags=["Dev.to Crawling"],
)
async def crawling_devto_hashnode(
    db: Database = Depends(_get_db),
):
    logger.info("manual devto_hashnode crawl triggered")
    from app.devto_hashnode.crawler import DevtoHashnodeCrawler

    result = await DevtoHashnodeCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="Dev.to 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "devto_hashnode",
        "crawler_detail": "trending_articles",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
