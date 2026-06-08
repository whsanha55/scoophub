# product_hunt/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class ProductHuntRouter(BaseRouter):
    table_name = "product_hunt"
    route_path = "/product-hunt"
    crawler_import = "app.community.product_hunt.crawler"
    crawler_class_name = "ProductHuntCrawler"
    api_tag = "Product Hunt"
    order_by = "votes_count DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = ProductHuntRouter()
router = _base.router
_get_db = _base.get_db_fn


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

    latest = await _base.get_latest(db)
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["fetched_at = $1"]
    params: list = [latest]
    idx = 2

    if topic is not None:
        conditions.append(f"topics::text LIKE ${idx}")
        params.append(f'%"{topic}"%')
        idx += 1

    if since is not None:
        conditions.append(f"posted_at >= ${idx}")
        params.append(since)
        idx += 1

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
