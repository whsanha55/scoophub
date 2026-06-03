# system/router.py
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends

from app.core.database import Database
from app.core.models import ApiResponse

router = APIRouter(prefix="/api", tags=["System"])


def get_db() -> Database:
    """Placeholder — overridden in create_app."""
    raise NotImplementedError


@router.get("/health")
async def health(db: Database = Depends(get_db)):
    news_count = await db.fetchval("SELECT COUNT(*) FROM news_articles")
    weather_count = await db.fetchval("SELECT COUNT(*) FROM weather_snapshots")
    return ApiResponse(
        success=True,
        data={
            "status": "ok",
            "total_records": {"news": news_count, "weather": weather_count},
        },
    )


@router.get("/crawl-logs")
async def crawl_logs(
    crawler: str | None = None,
    limit: int = 20,
    db: Database = Depends(get_db),
):
    if crawler:
        rows = await db.fetch(
            "SELECT * FROM crawl_logs WHERE crawler=$1 ORDER BY started_at DESC LIMIT $2",
            crawler,
            limit,
        )
    else:
        rows = await db.fetch(
            "SELECT * FROM crawl_logs ORDER BY started_at DESC LIMIT $1",
            limit,
        )
    logs = [dict(r) for r in rows]
    for log in logs:
        for key, val in log.items():
            if isinstance(val, datetime):
                log[key] = val.isoformat()
    return ApiResponse(success=True, data=logs, meta={"total": len(logs), "returned": len(logs)})
