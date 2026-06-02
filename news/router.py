# news/router.py
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse

from shared.database import Database
from shared.models import ApiResponse, ErrorBody

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


@router.get("/news")
async def get_news(
    minutes: int | None = None,
    fr: datetime | None = Query(None, alias="from"),
    to: datetime | None = Query(None, alias="to"),
    category: str | None = None,
    importance: str | None = None,
    limit: int = 20,
    db: Database = Depends(_get_db),
):
    conditions = []
    params: list = []
    idx = 1

    if minutes is not None:
        conditions.append(f"fetched_at >= NOW() - interval '{int(minutes)} minutes'")
    elif fr is not None and to is not None:
        conditions.append(f"fetched_at BETWEEN ${idx} AND ${idx + 1}")
        params.extend([fr, to])
        idx += 2
    else:
        # Default: last 30 minutes
        conditions.append("fetched_at >= NOW() - interval '30 minutes'")

    if category:
        conditions.append(f"category = ${idx}")
        params.append(category)
        idx += 1

    if importance:
        conditions.append(f"importance = ${idx}")
        params.append(importance)
        idx += 1

    where = " AND ".join(conditions)
    count_row = await db.fetchrow(f"SELECT COUNT(*) as cnt FROM news_articles WHERE {where}", *params)
    total = count_row["cnt"]

    params.append(limit)
    rows = await db.fetch(
        f"SELECT * FROM news_articles WHERE {where} ORDER BY fetched_at DESC LIMIT ${idx}",
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
