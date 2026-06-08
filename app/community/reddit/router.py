# reddit/router.py
from __future__ import annotations

import logging

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class RedditRouter(BaseRouter):
    table_name = "community_reddit"
    route_path = "/reddit"
    crawler_import = "app.community.reddit.crawler"
    crawler_class_name = "RedditCrawler"
    api_tag = "Reddit"
    order_by = "score DESC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = RedditRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/reddit",
    tags=["Reddit"],
    summary="Reddit 포스트 조회",
    description=(
        "최신 Reddit 포스트 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `subreddit`: 서브레딧 필터 (예: programming)\n"
        "- `min_score`: 최소 점수 필터\n"
        "- `since`: posted_at 기준 ISO 8601 필터\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n\n"
        "## 사용 예시\n"
        "- 프로그래밍 서브레딧 점수 100+: `?subreddit=programming&min_score=100`\n"
        "- 최근 1일 ML 포스트: `?subreddit=MachineLearning&since=2026-06-05T00:00:00Z`"
    ),
)
async def get_reddit(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    subreddit: str | None = Query(None, description="서브레딧 필터"),
    min_score: int | None = Query(None, description="최소 점수 필터"),
    since: str | None = Query(None, description="posted_at 기준 ISO 8601 필터"),
    db: Database = Depends(_get_db),
):
    logger.info("get_reddit requested: limit=%d subreddit=%s min_score=%s since=%s", limit, subreddit, min_score, since)

    latest = await _base.get_latest(db)
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["fetched_at = $1"]
    params: list = [latest]
    idx = 2

    if subreddit is not None:
        conditions.append(f"subreddit = ${idx}")
        params.append(subreddit)
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
