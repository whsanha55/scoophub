# rss_universal/router.py
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


# ────────────────────────────────────────────────────────────
#  Entries
# ────────────────────────────────────────────────────────────


@router.get(
    "/rss-universal/entries",
    tags=["RSS Universal"],
    summary="RSS 엔트리 조회",
    description=(
        "수집된 RSS 엔트리 목록을 반환합니다.\n\n"
        "- `limit`: 최대 반환 개수\n"
        "- `feed_id`: 특정 피드 필터\n"
        "- `since`: ISO 8601 시각 이후 엔트리만 (published_at >= since)"
    ),
)
async def get_entries(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    feed_id: int | None = Query(None, description="피드 ID 필터"),
    since: str | None = Query(None, description="ISO 8601 이후 필터 (published_at >= since)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_entries requested: limit=%d feed_id=%s since=%s", limit, feed_id, since)

    # 최신 fetched_at 기준
    latest = await db.fetchrow("SELECT MAX(fetched_at) AS latest FROM rss_entry")
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions = ["fetched_at = $1"]
    params: list = [latest["latest"]]
    idx = 2

    if feed_id is not None:
        conditions.append(f"feed_id = ${idx}")
        params.append(feed_id)
        idx += 1

    if since is not None:
        conditions.append(f"published_at >= ${idx}")
        params.append(since)
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM rss_entry WHERE {where} "
        f"ORDER BY published_at DESC NULLS LAST LIMIT ${idx}",
        *params,
        limit,
    )

    items = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=items, meta={"total": len(items), "returned": len(items)})


# ────────────────────────────────────────────────────────────
#  Feeds CRUD
# ────────────────────────────────────────────────────────────


@router.get(
    "/rss-universal/feeds",
    tags=["RSS Universal"],
    summary="RSS 피드 목록 조회",
    description="등록된 RSS 피드 목록을 반환합니다.",
)
async def get_feeds(db: Database = Depends(_get_db)):
    logger.info("get_feeds requested")
    rows = await db.fetch("SELECT * FROM rss_feed ORDER BY id")
    items = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=items, meta={"total": len(items), "returned": len(items)})


@router.post(
    "/rss-universal/feeds",
    tags=["RSS Universal"],
    summary="RSS 피드 등록",
    description="새로운 RSS 피드를 등록합니다.",
)
async def create_feed(
    url: str,
    name: str,
    site_url: str | None = None,
    db: Database = Depends(_get_db),
):
    logger.info("create_feed requested: url=%s name=%s", url, name)
    existing = await db.fetchrow("SELECT id FROM rss_feed WHERE url = $1", url)
    if existing:
        return JSONResponse(
            status_code=409,
            content=ApiResponse(
                success=False,
                error=ErrorDetail(code="already_exists", message=f"Feed already exists: {url}"),
            ).model_dump(),
        )

    row = await db.fetchrow(
        "INSERT INTO rss_feed (url, name, site_url) VALUES ($1, $2, $3) RETURNING *",
        url, name, site_url,
    )
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.delete(
    "/rss-universal/feeds/{feed_id}",
    tags=["RSS Universal"],
    summary="RSS 피드 삭제",
    description="RSS 피드를 삭제합니다. 연관된 엔트리도 함께 삭제됩니다.",
)
async def delete_feed(
    feed_id: int,
    db: Database = Depends(_get_db),
):
    logger.info("delete_feed requested: feed_id=%d", feed_id)
    row = await db.fetchrow("DELETE FROM rss_feed WHERE id = $1 RETURNING id", feed_id)
    if not row:
        return JSONResponse(
            status_code=404,
            content=ApiResponse(
                success=False,
                error=ErrorDetail(code="not_found", message=f"Feed not found: {feed_id}"),
            ).model_dump(),
        )
    return ApiResponse(success=True, data={"deleted": feed_id})


# ────────────────────────────────────────────────────────────
#  수동 크롤 트리거
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/rss-universal",
    summary="RSS Universal 크롤 수동 실행",
    description="등록된 모든 RSS 피드에서 엔트리를 수집합니다.",
    tags=["RSS Universal Crawling"],
)
async def crawling_rss_universal(db: Database = Depends(_get_db)):
    logger.info("manual rss_universal crawl triggered")
    from app.rss_universal.crawler import RssUniversalCrawler

    result = await RssUniversalCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="RSS Universal 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "rss_universal",
        "crawler_detail": "all_feeds",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
