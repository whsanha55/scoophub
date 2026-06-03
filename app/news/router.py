# news/router.py
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


@router.get(
    "/news",
    tags=["News"],
    summary="뉴스 기사 목록 조회",
    description=(
        "수집된 뉴스 기사를 최신순으로 반환합니다.\n\n"
        "- 시간 필터: `minutes` 또는 `from`/`to` 범위 지정 (둘 다 없으면 최근 30분)\n"
        "- `minutes`와 `from`/`to` 중 `minutes`가 우선"
    ),
)
async def get_news(
    minutes: int | None = Query(None, ge=1, description="최근 N분 이내 기사 조회"),
    fr: datetime | None = Query(None, alias="from", description="조회 시작 시각 (ISO 8601)"),
    to: datetime | None = Query(None, alias="to", description="조회 종료 시각 (ISO 8601)"),
    category: str | None = Query(None, description="카테고리 필터"),
    min_importance: int | None = Query(None, ge=1, le=10, description="최소 중요도 (1~10)"),
    limit: int = Query(20, ge=1, le=200, description="조회할 최대 기사 수"),
    db: Database = Depends(_get_db),
):
    logger.info("get_news 시작 - minutes=%s, category=%s, min_importance=%s, limit=%d", minutes, category, min_importance, limit)
    conditions = []
    params: list = []
    idx = 1

    if minutes is not None:
        conditions.append(f"created_at >= NOW() - interval '{int(minutes)} minutes'")
    elif fr is not None and to is not None:
        conditions.append(f"created_at BETWEEN ${idx} AND ${idx + 1}")
        params.extend([fr, to])
        idx += 2
    else:
        # Default: last 30 minutes
        conditions.append("created_at >= NOW() - interval '30 minutes'")

    if category:
        conditions.append(f"category = ${idx}")
        params.append(category)
        idx += 1

    if min_importance is not None:
        conditions.append(f"importance >= ${idx}")
        params.append(min_importance)
        idx += 1

    where = " AND ".join(conditions)
    count_row = await db.fetchrow(f"SELECT COUNT(*) as cnt FROM news_articles WHERE {where}", *params)
    total = count_row["cnt"]

    params.append(limit)
    rows = await db.fetch(
        f"SELECT * FROM news_articles WHERE {where} ORDER BY created_at DESC LIMIT ${idx}",
        *params,
    )

    articles = [_row_to_dict(r) for r in rows]
    logger.info("get_news 완료 - total=%d, returned=%d", total, len(articles))
    return ApiResponse(
        success=True,
        data=articles,
        meta={"total": total, "returned": len(articles)},
    )


@router.get(
    "/news/{article_id}",
    tags=["News"],
    summary="뉴스 기사 단건 조회",
    description="ID로 특정 뉴스 기사를 조회합니다.",
)
async def get_news_by_id(
    article_id: int,
    db: Database = Depends(_get_db),
):
    logger.info("get_news_by_id 시작 - article_id=%d", article_id)
    row = await db.fetchrow("SELECT * FROM news_articles WHERE id = $1", article_id)
    if not row:
        return JSONResponse(
            status_code=404,
            content=ApiResponse(
                success=False,
                error=ErrorDetail(
                    code="NOT_FOUND",
                    message=f"Article {article_id} not found",
                    detail="No news article exists with the given ID",
                    suggestion="Check the article ID or list articles with GET /api/news",
                ),
            ).model_dump(mode='json'),
        )
    return ApiResponse(success=True, data=_row_to_dict(row))


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
    "/crawling/news",
    summary="뉴스 크롤 수동 실행",
    description="RSS 피드를 수집해 뉴스 기사를 저장하고, 신규 기사를 LLM으로 요약합니다.",
    tags=["News Crawling"],
)
async def crawling_news(db: Database = Depends(_get_db)):
    """
    ## 📰 뉴스 크롤러

    | 항목      | 값         |
    |-----------|-----------|
    | 스케줄    | 매 15분   |
    | 소스      | RSS 피드  |
    | 저장 테이블 | `news_articles` |

    크롤 후 `summary_status='pending'` 기사를 20개 단위로 LLM 요약합니다.
    `config/settings.yaml` → `crawlers.news` 참조.
    """
    logger.info("crawling_news 시작 - 뉴스 크롤 수동 실행")
    from app.news.crawler import NewsCrawler

    result = await NewsCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="뉴스 크롤 실패"))

    summary = None
    try:
        from app.core.llm import LLMClient
        from app.news.summarizer import NewsSummarizer

        async with LLMClient() as llm:
            summary = await NewsSummarizer(db, llm).summarize_incomplete()
    except Exception as e:
        summary = {"error": f"요약 실패: {e}"}

    logger.info("crawling_news 완료 - items_fetched=%d, items_new=%d", result.items_fetched, result.items_new)
    return ApiResponse(success=True, data={
        "crawler": "news",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
        "summary": summary,
    })


# ────────────────────────────────────────────────────────────
#  수동 요약 재시도 (성공하지 않은 기사 전부)
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/news/summarize/retry",
    summary="뉴스 요약 재시도",
    description="최근 1일 이내이면서 아직 성공하지 않은(summary_status != 'success': pending/failed/error) 기사를 20개 단위로 다시 LLM 요약합니다.",
    tags=["News Crawling"],
)
async def summarize_news_retry(db: Database = Depends(_get_db)):
    logger.info("summarize_news_retry 시작 - 미완료 기사 요약 재시도")
    try:
        from app.core.llm import LLMClient
        from app.news.summarizer import NewsSummarizer

        async with LLMClient() as llm:
            result = await NewsSummarizer(db, llm).summarize_incomplete()
    except Exception as e:
        return ApiResponse(success=False, error=ErrorDetail(code="summarize_failed", message=f"요약 실패: {e}"))

    logger.info("summarize_news_retry 완료 - result=%s", result)
    return ApiResponse(success=True, data=result)
