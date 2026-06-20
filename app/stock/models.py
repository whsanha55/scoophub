# stock/models.py — Domain models for stock analysis.
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date, datetime
from enum import StrEnum

logger = logging.getLogger(__name__)


@dataclass
class Candle:
    """Generic OHLCV candle."""

    date: date
    open: float
    high: float
    low: float
    close: float
    volume: float
    ticker: str = ""
    interval: str = "1D"


@dataclass
class WeeklyExpectedMove:
    """Weekly expected move (1σ) for a ticker from usstocksigma.com."""

    id: int | None = None
    ticker: str = ""
    week_start: date | None = None
    week_end: date | None = None
    expected_move_high: float = 0.0
    expected_move_low: float = 0.0
    expected_move_pct: float = 0.0
    created_at: date | None = None
    updated_at: date | None = None


@dataclass
class SigmaResult:
    """Sigma computed from ATM straddle prices (Yahoo Finance options chain)."""

    id: int | None = None
    ticker: str = ""
    current_price: float = 0.0
    expiry_date: date | None = None

    # ATM straddle inputs
    atm_strike: float = 0.0
    atm_call: float = 0.0           # mid price (bid/ask avg), fallback lastPrice
    atm_put: float = 0.0

    # Computed expected move
    expected_move: float = 0.0      # atm_call + atm_put
    expected_move_pct: float = 0.0  # expected_move / current_price * 100

    # Snapshot metadata
    snapshot_date: date | None = None    # ET trading day
    snapshot_at: datetime | None = None  # UTC timestamp
    source: str = "yfinance_straddle"
    created_at: datetime | None = None

    # Volume flow / sentiment
    total_call_volume: int = 0
    total_put_volume: int = 0
    put_call_volume_ratio: float | None = None
    atm_call_volume: int = 0
    atm_put_volume: int = 0


class SigmaPosition(StrEnum):
    ABOVE_1SIGMA = "ABOVE_1SIGMA"
    WITHIN_UPPER = "WITHIN_UPPER"
    NEAR_CENTER = "NEAR_CENTER"
    WITHIN_LOWER = "WITHIN_LOWER"
    BELOW_1SIGMA = "BELOW_1SIGMA"


class SigmaSignalType(StrEnum):
    STRONG_BUY = "STRONG_BUY"
    BUY = "BUY"
    NEUTRAL = "NEUTRAL"
    SELL = "SELL"
    STRONG_SELL = "STRONG_SELL"


@dataclass
class SigmaRange:
    """Computed sigma range for a ticker's weekly expected move."""

    ticker: str = ""
    week_start: date | None = None
    week_end: date | None = None
    center: float = 0.0
    upper_1sigma: float = 0.0
    lower_1sigma: float = 0.0
    upper_2sigma: float = 0.0
    lower_2sigma: float = 0.0
    current_price: float = 0.0
    sigma_position: SigmaPosition = SigmaPosition.NEAR_CENTER


@dataclass
class SigmaSignal:
    """Investment signal based on sigma position."""

    ticker: str = ""
    date: date | None = None
    sigma_position: SigmaPosition = SigmaPosition.NEAR_CENTER
    signal: SigmaSignalType = SigmaSignalType.NEUTRAL
    confidence: float = 0.0
    price: float = 0.0
    sigma_range: SigmaRange | None = None


@dataclass
class WatchlistItem:
    """Watchlist item tracked by the user."""

    id: int | None = None
    ticker: str = ""
    exchange: str = ""
    name: str = ""
    memo: str | None = None
    added_at: date | None = None
    is_active: bool = True


@dataclass
class TickerAnalytics:
    """Aggregated analytics for a single ticker."""

    ticker: str = ""
    total_weeks: int = 0
    avg_expected_move_pct: float = 0.0
    max_expected_move_pct: float = 0.0
    min_expected_move_pct: float = 0.0


@dataclass
class TickerParams:
    """Per-ticker optimized parameters from walk-forward tuning."""

    id: int | None = None
    ticker: str = ""
    weights: dict[str, float] | None = None
    entry_threshold: float | None = None
    exit_threshold: float | None = None
    position_size_pct: float | None = None
    in_sample_sharpe: float | None = None
    in_sample_sortino: float | None = None
    out_sample_sharpe: float | None = None
    out_sample_sortino: float | None = None
    is_adopted: bool = False
    tuned_at: date | None = None


def compute_sigma_range(wem: WeeklyExpectedMove, current_price: float) -> SigmaRange:
    """Compute sigma range from a weekly expected move."""
    logger.info("compute_sigma_range: ticker=%s, price=%.2f, high=%.2f, low=%.2f", wem.ticker, current_price, wem.expected_move_high, wem.expected_move_low)
    # 중심가 = 주간 예상 최고가와 최저가의 중간값
    center = (wem.expected_move_high + wem.expected_move_low) / 2
    # 예상 변동폭의 절반 (1σ 추정의 기준)
    half_range = (wem.expected_move_high - wem.expected_move_low) / 2
    # crawler가 high/low를 ±1σ로 제공하므로 half_range 자체가 1σ
    sigma = half_range

    upper_1 = center + sigma
    lower_1 = center - sigma
    upper_2 = center + 2 * sigma
    lower_2 = center - 2 * sigma

    # 현재 가격이 sigma 구간 내 어디에 위치하는지 판별 (5단계 구간)
    if current_price >= upper_1:
        position = SigmaPosition.ABOVE_1SIGMA
    elif current_price >= center + sigma * 0.5:
        position = SigmaPosition.WITHIN_UPPER
    elif current_price >= center - sigma * 0.5:
        position = SigmaPosition.NEAR_CENTER
    elif current_price >= lower_1:
        position = SigmaPosition.WITHIN_LOWER
    else:
        position = SigmaPosition.BELOW_1SIGMA

    return SigmaRange(
        ticker=wem.ticker,
        week_start=wem.week_start,
        week_end=wem.week_end,
        center=center,
        upper_1sigma=upper_1,
        lower_1sigma=lower_1,
        upper_2sigma=upper_2,
        lower_2sigma=lower_2,
        current_price=current_price,
        sigma_position=position,
    )


def generate_sigma_signal(sigma_range: SigmaRange) -> SigmaSignal:
    """Generate investment signal from sigma position."""
    logger.info("generate_sigma_signal: ticker=%s, position=%s, price=%.2f", sigma_range.ticker, sigma_range.sigma_position.value, sigma_range.current_price)
    # sigma 위치 기반 역추세(contrarian) 시그널: 하방 극단 → 매수, 상방 극단 → 매도
    position = sigma_range.sigma_position

    # -1σ 이하: 과매도 구간 → 강한 매수 신호
    if position == SigmaPosition.BELOW_1SIGMA:
        signal = SigmaSignalType.STRONG_BUY
        confidence = 0.8
    # 하방 0.5σ~1σ: 저가 구간 → 매수 신호
    elif position == SigmaPosition.WITHIN_LOWER:
        signal = SigmaSignalType.BUY
        confidence = 0.6
    # 중심 ±0.5σ: 중립 구간
    elif position == SigmaPosition.NEAR_CENTER:
        signal = SigmaSignalType.NEUTRAL
        confidence = 0.3
    # 상방 0.5σ~1σ: 고가 구간 → 매도 신호
    elif position == SigmaPosition.WITHIN_UPPER:
        signal = SigmaSignalType.SELL
        confidence = 0.6
    # +1σ 이상: 과매수 구간 → 강한 매도 신호
    else:  # ABOVE_1SIGMA
        signal = SigmaSignalType.STRONG_SELL
        confidence = 0.8

    return SigmaSignal(
        ticker=sigma_range.ticker,
        date=sigma_range.week_start,
        sigma_position=position,
        signal=signal,
        confidence=confidence,
        price=sigma_range.current_price,
        sigma_range=sigma_range,
    )
