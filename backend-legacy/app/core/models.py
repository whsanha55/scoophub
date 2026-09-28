from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class ErrorDetail(BaseModel):
    code: str = Field(..., description="에러 식별 코드 (예: NOT_FOUND, crawl_failed)")
    message: str = Field(..., description="사용자 친화적 에러 메시지")
    detail: str | None = Field(None, description="추가 상세 정보")
    suggestion: str | None = Field(None, description="해결을 위한 다음 단계 제안")


# Alias for ErrorDetail to match the test import
ErrorBody = ErrorDetail


class ResponseMeta(BaseModel):
    requested_at: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc),
        description="요청 처리 시각 (UTC)",
    )
    total: int | None = Field(None, description="전체 결과 수")
    returned: int | None = Field(None, description="이번 응답에 포함된 항목 수")
    months: list[str] | None = Field(
        None, description="월별 조회 엔드포인트에서 파라미터 생략 시 전체 월 목록(YYYYMM)"
    )


class ApiResponse(BaseModel, Generic[T]):
    success: bool = Field(..., description="요청 처리 성공 여부")
    data: T | None = Field(None, description="응답 본문 데이터")
    error: ErrorDetail | None = Field(None, description="에러 정보 (성공 시 null)")
    meta: ResponseMeta = Field(default_factory=ResponseMeta, description="응답 메타 정보")
