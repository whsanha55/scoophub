from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class ErrorDetail(BaseModel):
    code: str
    message: str
    detail: str | None = None
    suggestion: str | None = None


# Alias for ErrorDetail to match the test import
ErrorBody = ErrorDetail


class ResponseMeta(BaseModel):
    requested_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    total: int | None = None
    returned: int | None = None


class ApiResponse(BaseModel, Generic[T]):
    success: bool
    data: T | None = None
    error: ErrorDetail | None = None
    meta: ResponseMeta = Field(default_factory=ResponseMeta)
