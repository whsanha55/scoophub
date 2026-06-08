# system/router.py
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

logger = logging.getLogger(__name__)

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
    description=(
        "서비스 상태와 주요 테이블 레코드 수를 반환합니다.\n\n"
        "## 확인 항목\n"
        "- API 서버 상태\n"
        "- 데이터베이스 연결 상태\n"
        "- 각 도메인별 테이블 레코드 수"
    ),
)
async def health(db: Database = Depends(get_db)):
    logger.info("health check requested")
    news_count = await db.fetchval("SELECT COUNT(*) FROM feed_news")
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
    description=(
        "요청 body의 message를 LLM에 전송해 응답을 반환합니다.\n\n"
        "## 용도\n"
        "- LLM API 키 유효성 검증\n"
        "- 모델 응답 상태 확인\n"
        "- 연결 상태 점검\n\n"
        "## 요청\n"
        "- `message`: LLM에 전송할 텍스트 (필수)"
    ),
)
async def llm_test(body: LLMTestRequest):
    logger.info("llm test requested: message=%r", body.message)
    from app.config import settings
    from app.core.llm import LLMClient

    try:
        async with LLMClient() as llm:
            content = await llm.chat(body.system, body.message)
        logger.info("llm test success: model=%s", settings.LLM_MODEL)
    except Exception as e:
        logger.error("llm test failed: %s", e)
        return ApiResponse(
            success=False,
            error=ErrorDetail(code="llm_failed", message=str(e) or type(e).__name__),
        )
    return ApiResponse(success=True, data={"model": settings.LLM_MODEL, "content": content})


@router.get(
    "/crawl-logs",
    summary="크롤 실행 로그 조회",
    description=(
        "크롤러 실행 이력을 최신순으로 반환합니다.\n\n"
        "## 필터\n"
        "- `crawler`: 크롤러 이름 (예: news, stock, weather)\n"
        "- `crawler_detail`: 크롤러 목적 (예: rss, sigma-scan, forecast)\n"
        "- `limit`: 최대 로그 수 (기본 20, 최대 200)\n\n"
        "## 사용 예시\n"
        "- 뉴스 크롤 로그: `?crawler=news`\n"
        "- 최근 50개: `?limit=50`"
    ),
)
async def crawl_logs(
    crawler: str | None = Query(None, description="크롤러 이름 필터 (예: news, stock, weather)"),
    crawler_detail: str | None = Query(None, description="크롤러 목적 필터 (예: rss, sigma-scan, forecast)"),
    limit: int = Query(20, ge=1, le=200, description="조회할 최대 로그 수"),
    db: Database = Depends(get_db),
):
    logger.info("crawl logs requested: crawler=%s detail=%s limit=%d", crawler, crawler_detail, limit)
    if crawler and crawler_detail:
        rows = await db.fetch(
            "SELECT * FROM crawl_logs WHERE crawler=$1 AND crawler_detail=$2 ORDER BY started_at DESC LIMIT $3",
            crawler, crawler_detail, limit,
        )
    elif crawler:
        rows = await db.fetch(
            "SELECT * FROM crawl_logs WHERE crawler=$1 ORDER BY started_at DESC LIMIT $2",
            crawler, limit,
        )
    elif crawler_detail:
        rows = await db.fetch(
            "SELECT * FROM crawl_logs WHERE crawler_detail=$1 ORDER BY started_at DESC LIMIT $2",
            crawler_detail, limit,
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
