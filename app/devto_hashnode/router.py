# devto_hashnode/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class DevtoHashnodeRouter(BaseRouter):
    table_name = "feed_devblog"
    route_path = "/devto-hashnode"
    crawler_import = "app.devto_hashnode.crawler"
    crawler_class_name = "DevtoHashnodeCrawler"
    api_tag = "Dev.to"
    order_by = "reactions_count DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = DevtoHashnodeRouter()
router = _base.router
_get_db = _base.get_db_fn


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

    latest = await _base.get_latest(db)
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["fetched_at = $1"]
    params: list = [latest]
    idx = 2

    if tag is not None:
        conditions.append(f"tags @> ${idx}::jsonb")
        params.append(f'["{tag}"]')
        idx += 1

    if since is not None:
        conditions.append(f"published_at >= ${idx}")
        params.append(since)
        idx += 1

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
