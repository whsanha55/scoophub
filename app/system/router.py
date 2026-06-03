# system/router.py
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

router = APIRouter(prefix="/api", tags=["System"])


class LLMTestRequest(BaseModel):
    message: str = Field(..., description="LLM에 전송할 사용자 메시지")
    system: str = Field("You are a helpful assistant.", description="시스템 프롬프트")


def get_db() -> Database:
    """Placeholder — overridden in create_app."""
    raise NotImplementedError


@router.get(
    "/health",
    summary="헬스 체크",
    description="서비스 상태와 주요 테이블 레코드 수를 반환합니다.",
)
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
    description="요청 body의 message를 LLM에 전송해 응답을 반환합니다. LLM 키·모델·연결 상태 점검용.",
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
            error=ErrorDetail(code="llm_failed", message=str(e) or type(e).__name__),
        )
    return ApiResponse(success=True, data={"model": settings.LLM_MODEL, "content": content})


@router.get(
    "/crawl-logs",
    summary="크롤 실행 로그 조회",
    description="크롤러 실행 이력을 최신순으로 반환합니다. 크롤러 이름으로 필터링 가능.",
)
async def crawl_logs(
    crawler: str | None = Query(None, description="크롤러 이름 필터 (예: news, weather, stock_sigma)"),
    limit: int = Query(20, ge=1, le=200, description="조회할 최대 로그 수"),
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
