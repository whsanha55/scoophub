# hacker_news/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class HNRouter(BaseRouter):
    table_name = "hacker_news"
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

    latest = await _base.get_latest(db)
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["fetched_at = $1"]
    params: list = [latest]
    idx = 2

    if item_type is not None:
        conditions.append(f"item_type = ${idx}")
        params.append(item_type)
        idx += 1

    if min_score is not None:
        conditions.append(f"score >= ${idx}")
        params.append(min_score)
        idx += 1

    if since is not None:
        conditions.append(f"posted_at >= ${idx}")
        params.append(since)
        idx += 1

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
