# reddit/router.py
from __future__ import annotations

import json
import logging
from datetime import datetime

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

    # crawl_data(category=community, purpose=reddit) 최신 배치.
    latest = await db.fetchval(
        "SELECT MAX((response->>'fetched_at')::timestamptz) FROM crawl_data "
        "WHERE category='community' AND purpose='reddit'"
    )
    if not latest:
        return _base.empty_response()

    conditions: list[str] = ["(response->>'fetched_at')::timestamptz = $1"]
    params: list = [latest]
    idx = 2

    if subreddit is not None:
        conditions.append(f"response->>'subreddit' = ${idx}")
        params.append(subreddit)
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
        f"WHERE category='community' AND purpose='reddit' AND {where} "
        f"ORDER BY (response->>'score')::int DESC NULLS LAST LIMIT ${idx}",
        *params, limit,
    )
    items = [_reddit_item(r) for r in rows]
    return _base.items_response(items)


def _reddit_item(row) -> dict:
    """crawl_data row → reddit 응답 필드로 재구성."""
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "id": row["id"],
        "reddit_id": resp.get("reddit_id"),
        "title": resp.get("title"),
        "author": resp.get("author"),
        "subreddit": resp.get("subreddit"),
        "score": resp.get("score", 0),
        "upvote_ratio": resp.get("upvote_ratio"),
        "num_comments": resp.get("num_comments"),
        "url": resp.get("url"),
        "permalink": resp.get("permalink"),
        "selftext": resp.get("selftext"),
        "is_self": resp.get("is_self"),
        "link_flair": resp.get("link_flair"),
        "domain": resp.get("domain"),
        "posted_at": resp.get("posted_at"),
        "fetched_at": resp.get("fetched_at"),
    }
