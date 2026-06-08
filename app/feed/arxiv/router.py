# arxiv/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class ArxivRouter(BaseRouter):
    table_name = "feed_arxiv"
    route_path = "/arxiv"
    crawler_import = "app.feed.arxiv.crawler"
    crawler_class_name = "ArxivCrawler"
    api_tag = "arXiv"
    order_by = "published_at DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = ArxivRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/arxiv",
    tags=["arXiv"],
    summary="arXiv 논문 조회",
    description=(
        "최신 arXiv 논문 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `category`: primary_category 필터 (예: cs.AI)\n"
        "- `since`: ISO 8601 날짜 (예: 2026-01-01)\n"
        "- `query`: 제목 검색 (ILIKE)\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- AI 논문: `?category=cs.AI`\n"
        "- transformer 관련: `?query=transformer`\n"
        "- 2026년 이후 ML 논문: `?category=cs.LG&since=2026-01-01`"
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

    latest = await _base.get_latest(db)
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["fetched_at = $1"]
    params: list = [latest]
    idx = 2

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

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
