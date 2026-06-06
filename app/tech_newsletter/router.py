# tech_newsletter/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class TechNewsletterRouter(BaseRouter):
    table_name = "tech_newsletter"
    route_path = "/tech-newsletter"
    api_tag = "Tech Newsletter"
    crawler_import = "app.tech_newsletter.crawler"
    crawler_class_name = "TechNewsletterCrawler"
    order_by = "published_at DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = TechNewsletterRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/tech-newsletter",
    tags=["Tech Newsletter"],
    summary="Tech Newsletter 아티클 조회",
    description=(
        "최신 Tech Newsletter 아티클 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `source`: 소스 필터 (예: TLDR Tech, TLDR AI, TechCrunch, The Verge)\n"
        "- `since`: published_at 기준 ISO 8601 필터\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- TLDR Tech만: `?source=TLDR Tech`\n"
        "- 최근 1일 전체: `?since=2026-06-05T00:00:00Z`\n"
        "- TechCrunch 최근 10개: `?source=TechCrunch&limit=10`"
    ),
)
async def get_tech_newsletter(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    source: str | None = Query(None, description="소스 필터"),
    since: str | None = Query(None, description="ISO 8601 이후 필터 (published_at >= since)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_tech_newsletter requested: limit=%d source=%s since=%s", limit, source, since)

    latest = await _base.get_latest(db)
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["fetched_at = $1"]
    params: list = [latest]
    idx = 2

    if source is not None:
        conditions.append(f"source = ${idx}")
        params.append(source)
        idx += 1

    if since is not None:
        conditions.append(f"published_at >= ${idx}")
        params.append(since)
        idx += 1

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
