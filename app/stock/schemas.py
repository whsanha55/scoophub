# stock/schemas.py — Pydantic API schemas.
from __future__ import annotations

from typing import Any

from pydantic import BaseModel


class AnalyzeResult(BaseModel):
    ticker: str
    status: str
    detail: str | None = None


class AnalyzeResponse(BaseModel):
    total: int
    ok: int
    errors: int
    results: list[AnalyzeResult]


class TechnicalOut(BaseModel):
    signal: str
    total_score: float
    confidence: float
    market_regime: str
    technical_scores: dict[str, int]
    technical_details: dict[str, float]


class SigmaOut(BaseModel):
    sigma_position: str
    sigma_signal: str
    sigma_confidence: float
    expected_move_pct: float
    expected_move_high: float
    expected_move_low: float
    weekly_moves: list[dict[str, Any]]


class StockReport(BaseModel):
    ticker: str
    exchange: str
    price: float
    change: float
    change_rate: float
    technical: TechnicalOut
    sigma: SigmaOut | None = None
    data_date: str | None = None
    is_stale: bool | None = None


class StockSummary(BaseModel):
    ticker: str
    exchange: str
    price: float
    change: float
    change_rate: float
    signal: str
    total_score: float
    confidence: float
    market_regime: str
    sigma_position: str
    sigma_signal: str
    sigma_confidence: float
    expected_move_pct: float
    data_date: str | None = None
    is_stale: bool | None = None


class WatchlistItemIn(BaseModel):
    ticker: str
    exchange: str = "NAS"
    name: str = ""
    memo: str | None = None


class WatchlistItemOut(BaseModel):
    id: str
    ticker: str
    exchange: str
    name: str
    memo: str | None
    added_at: str
    is_active: bool


class WatchlistUpdateIn(BaseModel):
    ticker: str | None = None
    exchange: str | None = None
    name: str | None = None
    memo: str | None = None
    is_active: bool | None = None
