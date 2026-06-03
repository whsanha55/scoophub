# system/router.py
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.core.database import Database
from app.core.models import ApiResponse

router = APIRouter(prefix="/api", tags=["System"])


class LLMTestRequest(BaseModel):
    message: str
    system: str = "You are a helpful assistant."


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


@router.post(
    "/llm/test",
    summary="LLM 호출 테스트",
    description="요청 body의 message를 그대로 LLM에 보내 응답을 반환합니다. (LLM 키·모델·연결 점검용)",
)
async def llm_test(body: LLMTestRequest):
    from app.config import settings
    from app.core.llm import LLMClient

    try:
        async with LLMClient() as llm:
            content = await llm.chat(body.system, body.message)
    except Exception as e:
        return ApiResponse(
            success=False,
            error={"code": "llm_failed", "message": str(e) or type(e).__name__},
        )
    return ApiResponse(success=True, data={"model": settings.LLM_MODEL, "content": content})


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
