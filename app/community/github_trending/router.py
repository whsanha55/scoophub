# github_trending/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class GithubTrendingRouter(BaseRouter):
    table_name = "community_github"
    route_path = "/github-trending"
    crawler_import = "app.community.github_trending.crawler"
    crawler_class_name = "GithubTrendingCrawler"
    api_tag = "GitHub Trending"
    order_by = "current_period_stars DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = GithubTrendingRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/github-trending",
    tags=["GitHub Trending"],
    summary="GitHub 트렌딩 리포지토리 조회",
    description=(
        "최신 GitHub 트렌딩 리포지토리 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `period`: daily / weekly / monthly (기본: daily)\n"
        "- `language`: 언어 필터 (예: python, typescript)\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- 주간 Python 트렌딩: `?period=weekly&language=python`\n"
        "- 월간 전체: `?period=monthly&limit=50`"
    ),
)
async def get_github_trending(
    period: str = Query("daily", description="조회 기간: daily / weekly / monthly"),
    language: str | None = Query(None, description="언어 필터"),
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    db: Database = Depends(_get_db),
):
    logger.info("get_github_trending requested: period=%s language=%s limit=%d", period, language, limit)
    conditions = ["period = $1"]
    params: list = [period]
    idx = 2

    if language is not None:
        conditions.append(f"language = ${idx}")
        params.append(language)
        idx += 1

    # 최신 fetched_at 기준으로 필터링 (period 조건 포함)
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM community_github WHERE period = $1",
        period,
    )
    if not latest or not latest["latest"]:
        return _base.empty_response()

    conditions.append(f"fetched_at = ${idx}")
    params.append(latest["latest"])
    idx += 1

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
