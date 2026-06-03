# news/router.py
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse

from app.core.database import Database
from app.core.models import ApiResponse, ErrorBody

router = APIRouter(prefix="/api", tags=["News"])


def _get_db() -> Database:
    raise NotImplementedError


@router.get("/news")
async def get_news(
    minutes: int | None = None,
    fr: datetime | None = Query(None, alias="from"),
    to: datetime | None = Query(None, alias="to"),
    category: str | None = None,
    min_importance: int | None = None,
    limit: int = 20,
    db: Database = Depends(_get_db),
):
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
    return ApiResponse(
        success=True,
        data=articles,
        meta={"total": total, "returned": len(articles)},
    )


@router.get("/news/{article_id}")
async def get_news_by_id(
    article_id: int,
    db: Database = Depends(_get_db),
):
    row = await db.fetchrow("SELECT * FROM news_articles WHERE id = $1", article_id)
    if not row:
        return JSONResponse(
            status_code=404,
            content=ApiResponse(
                success=False,
                error=ErrorBody(
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
    description="RSS 피드를 수집해 뉴스 기사를 저장합니다.",
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

    `config/settings.yaml` → `crawlers.news` 참조.
    """
    from app.news.crawler import NewsCrawler

    result = await NewsCrawler(db, cutoff_minutes=30).run()
    if result is None:
        return ApiResponse(success=False, error={"code": "crawl_failed", "message": "뉴스 크롤 실패"})
    return ApiResponse(success=True, data={
        "crawler": "news",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })


# ────────────────────────────────────────────────────────────
#  수동 요약 트리거
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/news/summarize",
    summary="뉴스 요약 수동 실행",
    description="요약되지 않은 뉴스 기사를 LLM으로 요약합니다.",
    tags=["News Crawling"],
)
async def summarize_news(db: Database = Depends(_get_db)):
    try:
        from app.core.llm import LLMClient
        from app.news.summarizer import NewsSummarizer

        async with LLMClient() as llm:
            summarizer = NewsSummarizer(db, llm)
            result = await summarizer.summarize_pending()
    except Exception as e:
        return ApiResponse(success=False, error={"code": "summarize_failed", "message": f"요약 실패: {e}"})

    return ApiResponse(success=True, data=result)
