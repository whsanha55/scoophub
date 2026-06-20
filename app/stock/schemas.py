# stock/schemas.py — Pydantic API schemas.
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class AnalyzeResult(BaseModel):
    ticker: str = Field(..., description="주식 티커 (예: AAPL)")
    status: str = Field(..., description="분석 결과 상태 (ok | error)")
    detail: str | None = Field(None, description="에러 시 상세 메시지")


class AnalyzeResponse(BaseModel):
    total: int = Field(..., description="전체 분석 대상 종목 수")
    ok: int = Field(..., description="성공한 종목 수")
    errors: int = Field(..., description="실패한 종목 수")
    results: list[AnalyzeResult] = Field(..., description="종목별 분석 결과 목록")


class TechnicalOut(BaseModel):
    signal: str = Field(..., description="매매 시그널 (STRONG_BUY | BUY | NEUTRAL | SELL | STRONG_SELL)")
    total_score: float = Field(..., description="기술 분석 종합 점수 (-100~100)")
    confidence: float = Field(..., description="분석 신뢰도 (0~1)")
    market_regime: str = Field(..., description="시장 국면 (bull | bear | neutral | volatile)")
    technical_scores: dict[str, int] = Field(..., description="개별 지표별 점수 (예: rsi: 65, macd: -10)")
    technical_details: dict[str, Any] = Field(..., description="개별 지표별 상세 값 (예: rsi_value: 65.2)")


class SigmaOut(BaseModel):
    sigma_position: str = Field(..., description="현재 가격의 Sigma 위치 (NEAR_UPPER | NEAR_CENTER | NEAR_LOWER)")
    sigma_signal: str = Field(..., description="Sigma 기반 시그널 (OVERBOUGHT | NEUTRAL | OVERSOLD)")
    sigma_confidence: float = Field(..., description="Sigma 시그널 신뢰도 (0~1)")
    expected_move_pct: float = Field(..., description="이번 주 예상 변동폭 (%)")
    expected_move_high: float = Field(..., description="예상 상방 가격 (+1σ)")
    expected_move_low: float = Field(..., description="예상 하방 가격 (-1σ)")
    weekly_moves: list[dict[str, Any]] = Field(default_factory=list, description="최근 주간 예상 변동폭 이력")


class ActionableLevelsOut(BaseModel):
    target_price: float | None = Field(None, description="목표가 (+1σ)")
    buy_zone: float | None = Field(None, description="매수 구간 (-1σ)")
    stop_loss: float | None = Field(None, description="손절가 (price - 1.5×ATR)")
    momentum_fire: bool = Field(False, description="불타기 진입 여부 (price>EMA12 & MACD hist>0)")


class StockReport(BaseModel):
    ticker: str = Field(..., description="주식 티커 (예: AAPL)")
    exchange: str = Field(..., description="거래소 코드 (예: NAS, NYE)")
    price: float = Field(..., description="현재가")
    change: float = Field(..., description="전일 대비 가격 변화")
    change_rate: float = Field(..., description="전일 대비 등락률 (%)")
    technical: TechnicalOut = Field(..., description="기술 분석 결과")
    sigma: SigmaOut | None = Field(None, description="Sigma(1σ) 주간 예상 변동폭 데이터")
    actionable_levels: ActionableLevelsOut | None = Field(None, description="액션러블 거래 레벨 (목표가/매수/손절/불타기)")
    hit_rate: float | None = Field(None, description="히트율 (현재 null — 스키마 히스토리 누적 전까지 미산출)")
    group: str | None = Field(None, description="계층 (market | sector | individual)")
    data_date: str | None = Field(None, description="분석 기준 날짜 (ISO 8601)")
    is_stale: bool | None = Field(None, description="분석 결과가 24시간 이전인지 여부")


class StockSummary(BaseModel):
    ticker: str = Field(..., description="주식 티커 (예: AAPL)")
    exchange: str = Field(..., description="거래소 코드 (예: NAS, NYE)")
    price: float = Field(..., description="현재가")
    change: float = Field(..., description="전일 대비 가격 변화")
    change_rate: float = Field(..., description="전일 대비 등락률 (%)")
    signal: str = Field(..., description="매매 시그널")
    total_score: float = Field(..., description="기술 분석 종합 점수")
    confidence: float = Field(..., description="분석 신뢰도")
    market_regime: str = Field(..., description="시장 국면")
    sigma_position: str = Field(..., description="Sigma 위치")
    sigma_signal: str = Field(..., description="Sigma 시그널")
    sigma_confidence: float = Field(..., description="Sigma 신뢰도")
    expected_move_pct: float = Field(..., description="예상 변동폭 (%)")
    actionable_levels: ActionableLevelsOut | None = Field(None, description="액션러블 거래 레벨")
    hit_rate: float | None = Field(None, description="히트율 (현재 null)")
    group: str | None = Field(None, description="계층 (market | sector | individual)")
    data_date: str | None = Field(None, description="분석 기준 날짜 (ISO 8601)")
    is_stale: bool | None = Field(None, description="분석 결과가 24시간 이전인지 여부")


class WatchlistItemIn(BaseModel):
    ticker: str = Field(..., description="주식 티커 (예: AAPL). 자동 대문자 변환.")
    exchange: str = Field("NAS", description="거래소 코드 (예: NAS, NYE)")
    name: str = Field("", description="종목명 (선택)")
    memo: str | None = Field(None, description="메모 (선택)")
    group: str = Field("individual", description="계층 (market | sector | individual). 기본 individual.")


class SigmaDataOut(BaseModel):
    ticker: str = Field(..., description="주식 티커")
    current_price: float = Field(..., description="현재가")
    expiry_date: str | None = Field(None, description="옵션 만기일 (ISO 8601)")
    atm_strike: float = Field(..., description="ATM 행사가")
    atm_call: float = Field(..., description="ATM 콜 가격 (mid or lastPrice)")
    atm_put: float = Field(..., description="ATM 풋 가격 (mid or lastPrice)")
    expected_move: float = Field(..., description="예상 변동폭 (ATM call + put)")
    expected_move_pct: float = Field(..., description="예상 변동폭 (%)")
    snapshot_date: str | None = Field(None, description="스냅샷 날짜 (ET 거래일, ISO 8601)")
    snapshot_at: str | None = Field(None, description="스냅샷 시각 (UTC, ISO 8601)")
    source: str = Field(..., description="데이터 출처")
    total_call_volume: int = Field(0, description="만기 전체 콜 거래량")
    total_put_volume: int = Field(0, description="만기 전체 풋 거래량")
    put_call_volume_ratio: float | None = Field(None, description="풋/콜 거래량 비율")
    atm_call_volume: int = Field(0, description="ATM 콜 거래량")
    atm_put_volume: int = Field(0, description="ATM 풋 거래량")
    created_at: str | None = Field(None, description="생성 일시 (ISO 8601)")


class WatchlistItemOut(BaseModel):
    id: str = Field(..., description="종목 고유 ID")
    ticker: str = Field(..., description="주식 티커")
    exchange: str = Field(..., description="거래소 코드")
    name: str = Field(..., description="종목명")
    memo: str | None = Field(None, description="메모")
    added_at: str = Field(..., description="추가 일시 (ISO 8601)")
    is_active: bool = Field(..., description="활성 상태 여부")
    group: str = Field("individual", description="계층 (market | sector | individual)")


class WatchlistUpdateIn(BaseModel):
    ticker: str | None = Field(None, description="주식 티커")
    exchange: str | None = Field(None, description="거래소 코드")
    name: str | None = Field(None, description="종목명")
    memo: str | None = Field(None, description="메모")
    is_active: bool | None = Field(None, description="활성 상태 여부 (false 시 비활성화)")
    group: str | None = Field(None, description="계층 (market | sector | individual)")
