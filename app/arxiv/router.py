# arxiv/router.py
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
    "/arxiv",
    tags=["arXiv"],
    summary="arXiv 논문 조회",
    description=(
        "최신 arXiv 논문 목록을 반환합니다.\n\n"
        "- `category`: primary_category 필터 (예: cs.AI)\n"
        "- `since`: ISO 8601 날짜 (예: 2025-01-01)\n"
        "- `query`: 제목 검색 (ILIKE)\n"
        "- `limit`: 최대 반환 개수"
    ),
)
async def get_arxiv(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    category: str | None = Query(None, description="primary_category 필터"),
    since: str | None = Query(None, description="ISO 8601 날짜 (published_at >=)"),
    query: str | None = Query(None, description="제목 검색 (ILIKE)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_arxiv requested: category=%s since=%s query=%s limit=%d", category, since, query, limit)
    conditions: list[str] = []
    params: list = []
    idx = 1

    # 최신 fetched_at 기준으로 필터링
    latest = await db.fetchrow("SELECT MAX(fetched_at) AS latest FROM arxiv_papers")
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions.append(f"fetched_at = ${idx}")
    params.append(latest["latest"])
    idx += 1

    if category is not None:
        conditions.append(f"primary_category = ${idx}")
        params.append(category)
        idx += 1

    if since is not None:
        conditions.append(f"published_at >= ${idx}")
        params.append(since)
        idx += 1

    if query is not None:
        conditions.append(f"title ILIKE ${idx}")
        params.append(f"%{query}%")
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM arxiv_papers WHERE {where} "
        f"ORDER BY published_at DESC NULLS LAST LIMIT ${idx}",
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
    "/crawling/arxiv",
    summary="arXiv 크롤 수동 실행",
    description="arXiv 논문을 수집합니다.",
    tags=["arXiv Crawling"],
)
async def crawling_arxiv(db: Database = Depends(_get_db)):
    logger.info("manual arxiv crawl triggered")
    from app.arxiv.crawler import ArxivCrawler

    result = await ArxivCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="arXiv 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "arxiv",
        "crawler_detail": "daily_papers",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
