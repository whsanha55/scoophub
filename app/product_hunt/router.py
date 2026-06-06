# product_hunt/router.py
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


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


@router.get(
    "/product-hunt",
    tags=["Product Hunt"],
    summary="Product Hunt 게시물 조회",
    description=(
        "최신 Product Hunt 게시물 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `topic`: 토픽 필터 (예: AI, SaaS)\n"
        "- `since`: ISO 8601 날짜 이후 게시물만\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- AI 관련: `?topic=AI`\n"
        "- 최근 1주일: `?since=2026-05-30T00:00:00Z`"
    ),
)
async def get_product_hunt(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    topic: str | None = Query(None, description="토픽 필터"),
    since: str | None = Query(None, description="ISO 8601 날짜 (이후 게시물만)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_product_hunt requested: limit=%d topic=%s since=%s", limit, topic, since)

    # 최신 fetched_at 기준
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM product_hunt"
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions = ["fetched_at = $1"]
    params: list = [latest["latest"]]
    idx = 2

    if topic is not None:
        conditions.append(f"topics::text LIKE ${idx}")
        params.append(f'%"{topic}"%')
        idx += 1

    if since is not None:
        conditions.append(f"posted_at >= ${idx}")
        params.append(since)
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM product_hunt WHERE {where} "
        f"ORDER BY votes_count DESC NULLS LAST LIMIT ${idx}",
        *params,
        limit,
    )

    items = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=items, meta={"total": len(items), "returned": len(items)})


# ────────────────────────────────────────────────────────────
#  수동 크롤 트리거
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/product-hunt",
    summary="Product Hunt 크롤 수동 실행",
    description=(
        "Product Hunt API에서 오늘의 게시물을 수집합니다.\n\n"
        "## 자동 스케줄\n"
        "- Cron: `0 11 * * *` (KST, 매일 11:00)\n"
        "- 설정: `config/settings.yaml` → `crawlers.product_hunt`\n\n"
        "## 수집 범위\n"
        "- max_posts: 30\n"
        "- source_timeout_seconds: 15\n"
        "- retry_count: 3\n\n"
        "## 수동 실행\n"
        "스케줄과 무관하게 즉시 크롤을 트리거합니다.\n\n"
        "## 응답\n"
        "- `items_fetched`: 수집된 전체 아이템 수\n"
        "- `items_new`: 신규 저장 아이템 수\n"
        "- `errors`: 오류 목록 (없으면 null)"
    ),
    tags=["Product Hunt Crawling"],
)
async def crawling_product_hunt(db: Database = Depends(_get_db)):
    logger.info("manual product_hunt crawl triggered")
    from app.product_hunt.crawler import ProductHuntCrawler
    import yaml

    with open("config/settings.yaml") as f:
        cfg = yaml.safe_load(f)

    ph_cfg = cfg.get("crawlers", {}).get("product_hunt", {})
    cfg_token = ph_cfg.get("developer_token", "")

    result = await ProductHuntCrawler(db, developer_token=cfg_token).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="Product Hunt 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "product_hunt",
        "crawler_detail": "daily_launches",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
