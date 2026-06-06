# github_trending/router.py
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, Query

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


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

    # 최신 fetched_at 기준으로 필터링
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM github_trending_repos WHERE period = $1",
        period,
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions.append(f"fetched_at = ${idx}")
    params.append(latest["latest"])
    idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM github_trending_repos WHERE {where} "
        f"ORDER BY current_period_stars DESC NULLS LAST LIMIT ${idx}",
        *params,
        limit,
    )

    items = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=items, meta={"total": len(items), "returned": len(items)})


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


# ────────────────────────────────────────────────────────────
#  수동 크롤 트리거
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/github-trending",
    summary="GitHub 트렌딩 크롤 수동 실행",
    description=(
        "gtrending 라이브러리로 GitHub 트렌딩 리포지토리를 수집합니다.\n\n"
        "## 자동 스케줄\n"
        "- Cron: `0 9 * * *` (KST, 매일 09:00)\n"
        "- 설정: `config/settings.yaml` → `crawlers.github_trending`\n\n"
        "## 수집 범위\n"
        "- since: daily\n"
        "- language: null (전체 언어)\n"
        "- max_repos: 25\n\n"
        "## 수동 실행\n"
        "스케줄과 무관하게 즉시 크롤을 트리거합니다.\n\n"
        "## 응답\n"
        "- `items_fetched`: 수집된 전체 아이템 수\n"
        "- `items_new`: 신규 저장 아이템 수\n"
        "- `errors`: 오류 목록 (없으면 null)"
    ),
    tags=["GitHub Trending Crawling"],
)
async def crawling_github_trending(db: Database = Depends(_get_db)):
    logger.info("manual github_trending crawl triggered")
    from app.github_trending.crawler import GithubTrendingCrawler

    result = await GithubTrendingCrawler(db, since="daily").run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="GitHub 트렌딩 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "github_trending",
        "crawler_detail": "daily",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
