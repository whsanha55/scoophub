# tech_newsletter/router.py
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
    "/tech-newsletter",
    tags=["Tech Newsletter"],
    summary="Tech Newsletter 아티클 조회",
    description=(
        "최신 Tech Newsletter 아티클 목록을 반환합니다.\n\n"
        "- `limit`: 최대 반환 개수\n"
        "- `source`: 소스 필터 (예: TLDR Tech, TechCrunch)\n"
        "- `since`: ISO 8601 형식 이후 게시글 필터"
    ),
)
async def get_tech_newsletter(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    source: str | None = Query(None, description="소스 필터"),
    since: str | None = Query(None, description="ISO 8601 이후 필터 (published_at >= since)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_tech_newsletter requested: limit=%d source=%s since=%s", limit, source, since)
    conditions = []
    params: list = []
    idx = 1

    # 최신 fetched_at 기준으로 필터링
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM tech_newsletter",
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions.append(f"fetched_at = ${idx}")
    params.append(latest["latest"])
    idx += 1

    if source is not None:
        conditions.append(f"source = ${idx}")
        params.append(source)
        idx += 1

    if since is not None:
        since_dt = datetime.fromisoformat(since)
        conditions.append(f"published_at >= ${idx}")
        params.append(since_dt)
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM tech_newsletter WHERE {where} "
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
    "/crawling/tech-newsletter",
    summary="Tech Newsletter 크롤 수동 실행",
    description="RSS 피드로 Tech Newsletter 아티클을 수집합니다.",
    tags=["Tech Newsletter Crawling"],
)
async def crawling_tech_newsletter(db: Database = Depends(_get_db)):
    logger.info("manual tech_newsletter crawl triggered")
    import yaml
    from app.tech_newsletter.crawler import TechNewsletterCrawler

    with open("config/settings.yaml") as f:
        cfg = yaml.safe_load(f)
    cfg_feeds = cfg["crawlers"]["tech_newsletter"].get("feeds")

    result = await TechNewsletterCrawler(db, feeds=cfg_feeds).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="Tech Newsletter 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "tech_newsletter",
        "crawler_detail": "rss_feeds",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
