# github_trending/router.py
from __future__ import annotations

import json
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

    # crawl_data(category=community, purpose=github).
    # period별 최신 배치 = response.fetched_at의 MAX (해당 period).
    latest = await db.fetchval(
        "SELECT MAX((response->>'fetched_at')::timestamptz) FROM crawl_data "
        "WHERE category='community' AND purpose='github' AND response->>'period'=$1",
        period,
    )
    if not latest:
        return _base.empty_response()

    conditions = [
        "response->>'period' = $1",
        "(response->>'fetched_at')::timestamptz = $2",
    ]
    params: list = [period, latest]
    idx = 3

    if language is not None:
        conditions.append(f"response->>'language' = ${idx}")
        params.append(language)
        idx += 1

    where = " AND ".join(conditions)
    rows = await db.fetch(
        f"SELECT id, key, date_at, response "
        f"FROM crawl_data "
        f"WHERE category='community' AND purpose='github' AND {where} "
        f"ORDER BY (response->>'current_period_stars')::int DESC NULLS LAST LIMIT ${idx}",
        *params, limit,
    )
    items = [_github_item(r) for r in rows]
    return _base.items_response(items)


def _github_item(row) -> dict:
    """crawl_data row → github trending 응답 필드로 재구성."""
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "id": row["id"],
        "fullname": resp.get("fullname"),
        "author": resp.get("author"),
        "name": resp.get("name"),
        "url": resp.get("url"),
        "description": resp.get("description"),
        "language": resp.get("language"),
        "stars": resp.get("stars", 0),
        "forks": resp.get("forks", 0),
        "current_period_stars": resp.get("current_period_stars", 0),
        "period": resp.get("period"),
        "fetched_at": resp.get("fetched_at"),
    }
