# arxiv/router.py
from __future__ import annotations

import json
import logging
from datetime import datetime

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

    # crawl_data(category=feed, purpose=arxiv) 최신 배치.
    latest = await db.fetchval(
        "SELECT MAX((response->>'fetched_at')::timestamptz) FROM crawl_data "
        "WHERE category='feed' AND purpose='arxiv'"
    )
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["(response->>'fetched_at')::timestamptz = $1"]
    params: list = [latest]
    idx = 2

    if category is not None:
        conditions.append(f"response->>'primary_category' = ${idx}")
        params.append(category)
        idx += 1

    if since is not None:
        since_dt = datetime.fromisoformat(since.replace("Z", "+00:00"))
        conditions.append(f"(response->>'published_at')::timestamptz >= ${idx}")
        params.append(since_dt)
        idx += 1

    if query is not None:
        conditions.append(f"response->>'title' ILIKE ${idx}")
        params.append(f"%{query}%")
        idx += 1

    where = " AND ".join(conditions)
    rows = await db.fetch(
        f"SELECT id, key, date_at, response "
        f"FROM crawl_data "
        f"WHERE category='feed' AND purpose='arxiv' AND {where} "
        f"ORDER BY date_at DESC NULLS LAST LIMIT ${idx}",
        *params, limit,
    )
    items = [_arxiv_item(r) for r in rows]
    return _base.items_response(items)


def _arxiv_item(row) -> dict:
    """crawl_data row → arxiv 응답 필드로 재구성."""
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "id": row["id"],
        "arxiv_id": resp.get("arxiv_id"),
        "title": resp.get("title"),
        "authors": resp.get("authors", []),
        "summary": resp.get("summary"),
        "primary_category": resp.get("primary_category"),
        "categories": resp.get("categories", []),
        "pdf_url": resp.get("pdf_url"),
        "abstract_url": resp.get("abstract_url"),
        "published_at": resp.get("published_at"),
        "updated_at": resp.get("updated_at"),
        "author_comment": resp.get("author_comment"),
        "journal_ref": resp.get("journal_ref"),
        "fetched_at": resp.get("fetched_at"),
    }
