# hacker_news/router.py
from __future__ import annotations

import json
import logging
from datetime import datetime

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class HNRouter(BaseRouter):
    table_name = "community_hackernews"
    route_path = "/hacker-news"
    api_tag = "Hacker News"
    crawler_import = "app.community.hacker_news.crawler"
    crawler_class_name = "HackerNewsCrawler"
    order_by = "score DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = HNRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/hacker-news",
    tags=["Hacker News"],
    summary="Hacker News 아이템 조회",
    description=(
        "최신 Hacker News 아이템 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `item_type`: story / ask_hn / show_hn / job (기본: story)\n"
        "- `min_score`: 최소 점수 필터\n"
        "- `since`: ISO 8601 시간 필터 (예: 2026-06-06T00:00:00Z)\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- 점수 100+ 스토리: `?min_score=100`\n"
        "- Show HN 최근 1일: `?item_type=show_hn&since=2026-06-05T00:00:00Z`"
    ),
)
async def get_hacker_news(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    min_score: int | None = Query(None, description="최소 점수 필터"),
    item_type: str = Query("story", description="아이템 타입: story / ask_hn / show_hn / job"),
    since: str | None = Query(None, description="ISO 8601 시간 필터"),
    db: Database = Depends(_get_db),
):
    logger.info("get_hacker_news requested: limit=%d min_score=%s item_type=%s since=%s", limit, min_score, item_type, since)

    # community_hackernews → crawl_data(category=community, purpose=hackernews).
    latest = await db.fetchval(
        "SELECT MAX((response->>'fetched_at')::timestamptz) FROM crawl_data "
        "WHERE category='community' AND purpose='hackernews'"
    )
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["(response->>'fetched_at')::timestamptz = $1"]
    params: list = [latest]
    idx = 2

    if item_type is not None:
        conditions.append(f"response->>'item_type' = ${idx}")
        params.append(item_type)
        idx += 1

    if min_score is not None:
        conditions.append(f"(response->>'score')::int >= ${idx}")
        params.append(min_score)
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
        f"WHERE category='community' AND purpose='hackernews' AND {where} "
        f"ORDER BY (response->>'score')::int DESC NULLS LAST LIMIT ${idx}",
        *params, limit,
    )
    items = [_hn_item(r) for r in rows]
    return _base.items_response(items)


def _hn_item(row) -> dict:
    """crawl_data row → 기존 community_hackernews 응답 필드로 재구성."""
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "id": row["id"],
        "hn_id": resp.get("hn_id"),
        "title": resp.get("title"),
        "url": resp.get("url"),
        "by_user": resp.get("by_user"),
        "score": resp.get("score", 0),
        "descendants": resp.get("descendants"),
        "item_type": resp.get("item_type"),
        "body_text": resp.get("body_text"),
        "posted_at": resp.get("posted_at"),
        "fetched_at": resp.get("fetched_at"),
    }
