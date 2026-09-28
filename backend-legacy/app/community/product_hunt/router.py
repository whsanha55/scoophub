# product_hunt/router.py
from __future__ import annotations

import json
import logging
from datetime import datetime

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class ProductHuntRouter(BaseRouter):
    table_name = "community_producthunt"
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

    # crawl_data(category=community, purpose=producthunt) 최신 배치.
    latest = await db.fetchval(
        "SELECT MAX((response->>'fetched_at')::timestamptz) FROM crawl_data "
        "WHERE category='community' AND purpose='producthunt'"
    )
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["(response->>'fetched_at')::timestamptz = $1"]
    params: list = [latest]
    idx = 2

    if topic is not None:
        conditions.append(f"response->'topics' @> ${idx}::jsonb")
        params.append(f'["{topic}"]')
        idx += 1

    if since is not None:
        since_dt = datetime.fromisoformat(since.replace("Z", "+00:00"))
        conditions.append(f"(response->>'posted_at')::timestamptz >= ${idx}")
        params.append(since_dt)
        idx += 1

    where = " AND ".join(conditions)
    rows = await db.fetch(
        f"SELECT id, key, date_at, response "
        f"FROM crawl_data "
        f"WHERE category='community' AND purpose='producthunt' AND {where} "
        f"ORDER BY (response->>'votes_count')::int DESC NULLS LAST LIMIT ${idx}",
        *params, limit,
    )
    items = [_ph_item(r) for r in rows]
    return _base.items_response(items)


def _ph_item(row) -> dict:
    """crawl_data row → product hunt 응답 필드로 재구성."""
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "id": row["id"],
        "ph_id": resp.get("ph_id"),
        "name": resp.get("name"),
        "tagline": resp.get("tagline"),
        "slug": resp.get("slug"),
        "ph_url": resp.get("ph_url"),
        "website_url": resp.get("website_url"),
        "votes_count": resp.get("votes_count", 0),
        "comments_count": resp.get("comments_count", 0),
        "topics": resp.get("topics", []),
        "featured_at": resp.get("featured_at"),
        "posted_at": resp.get("posted_at"),
        "fetched_at": resp.get("fetched_at"),
    }
