"""Signal generation: combine technical indicators into BUY/SELL/HOLD."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from enum import StrEnum

from app.stock.technical import TechnicalResult, analyze, technical_score

logger = logging.getLogger(__name__)


class Signal(StrEnum):
    STRONG_BUY = "STRONG_BUY"
    BUY = "BUY"
    HOLD = "HOLD"
    SELL = "SELL"
    STRONG_SELL = "STRONG_SELL"


class MarketRegime(StrEnum):
    TRENDING_UP = "TRENDING_UP"
    TRENDING_DOWN = "TRENDING_DOWN"
    RANGING = "RANGING"
    CHOPPY = "CHOPPY"


@dataclass
class AnalysisReport:
    ticker: str
    signal: Signal
    total_score: float
    confidence: float
    signal_quality: str  # "strong", "moderate", "weak"
    market_regime: MarketRegime
    technical_scores: dict[str, int]
    technical_weights: dict[str, float]
    price: float
    change: float
    change_rate: float
    technical_details: TechnicalResult


def _detect_regime(result: TechnicalResult, price: float) -> MarketRegime:
    logger.info("_detect_regime: adx=%.1f, bb_width=%.4f, price=%.2f, ema12=%.2f", result.adx, result.bb_width, price, result.ema12)
    # Choppy: 매우 낮은 ADX(방향성 없음) + 넓은 BB(변동성 높음) = 노이즈 시장
    if result.adx < 15 and result.bb_width > 0.06:
        return MarketRegime.CHOPPY
    # Ranging: 낮은 ADX = 뚜렷한 추세 없음 (박스권)
    if result.adx < 20:
        return MarketRegime.RANGING
    # TRENDING_UP: 가격 > EMA12 + MACD 선 > 시그널선 (상승 추세 확인)
    if price > result.ema12 and result.macd_line > result.macd_signal:
        return MarketRegime.TRENDING_UP
    # TRENDING_DOWN: 가격 < EMA12 + MACD 선 < 시그널선 (하락 추세 확인)
    if price < result.ema12 and result.macd_line < result.macd_signal:
        return MarketRegime.TRENDING_DOWN
    return MarketRegime.RANGING


def _dynamic_weights(regime: MarketRegime) -> dict[str, float]:
    """Adjust indicator weights by market regime."""
    logger.info("_dynamic_weights: regime=%s", regime.value)
    # 추세장에서는 MACD/MA 가중치 ↑ (추세 추종), RSI/BB/Stochastic 가중치 ↓ (과매수/과매도 신호 약화)
    if regime == MarketRegime.TRENDING_UP:
        return {
            "ma": 1.2,
            "rsi": 0.8,
            "macd": 1.5,
            "bb": 0.6,
            "stochastic": 0.5,
            "adx": 1.0,
            "vwap": 1.0,
        }
    # 하락 추세에서는 RSI 가중치 ↑ (과매도 반등 포착), MACD 가중치 ↑ (하락 모멘텀 확인)
    if regime == MarketRegime.TRENDING_DOWN:
        return {
            "ma": 1.2,
            "rsi": 1.3,
            "macd": 1.5,
            "bb": 0.8,
            "stochastic": 0.6,
            "adx": 1.0,
            "vwap": 1.0,
        }
    # Choppy: 모든 지표 신뢰도 낮음 → 가중치 전반적으로 축소
    if regime == MarketRegime.CHOPPY:
        return {
            "ma": 0.5,
            "rsi": 0.6,
            "macd": 0.4,
            "bb": 0.5,
            "stochastic": 0.5,
            "adx": 0.3,
            "vwap": 0.5,
        }
    # Ranging: 평균 회귀 지표(RSI, BB, Stochastic) 가중치 ↑ (과매수/과매도 구간 반전 기대)
    return {
        "ma": 0.7,
        "rsi": 1.3,
        "macd": 0.6,
        "bb": 1.4,
        "stochastic": 1.3,
        "adx": 0.4,
        "vwap": 1.1,
    }


def _calc_confidence(
    scores: dict[str, int],
    weights: dict[str, float],
    regime: MarketRegime,
) -> float:
    """Confidence score 0–100 based on indicator agreement and strength."""
    if not scores:
        return 0.0

    logger.info("_calc_confidence: regime=%s, scores=%s", regime.value, scores)

    # 가중 합산 점수 (방향성 판단의 기준)
    weighted_sum = sum(scores[k] * weights.get(k, 1.0) for k in scores)
    # 가중 절대값 합 (정규화를 위한 최대 가능 범위)
    max_possible = sum(abs(scores[k]) * weights.get(k, 1.0) for k in scores)

    if max_possible == 0:
        return 50.0

    # 지표 합의도(Agreement): 주도 방향에 동의하는 지표 비율
    direction = 1 if weighted_sum > 0 else (-1 if weighted_sum < 0 else 0)
    if direction != 0:
        agreeing = sum(
            1 for k, v in scores.items()
            if (v > 0 and direction > 0) or (v < 0 and direction < 0)
        )
        total_active = sum(1 for v in scores.values() if v != 0)
        agreement_ratio = agreeing / total_active if total_active > 0 else 0.0
    else:
        agreement_ratio = 0.0

    # 강도(Strength): 방향성의 절대 크기 (0~1 정규화)
    strength = abs(weighted_sum) / max_possible

    # 기본 신뢰도 = 합의도(최대 60%) + 강도(최대 30%)
    base_conf = agreement_ratio * 60 + strength * 30

    # 시장 국면 보정: 추세장은 신뢰도 보너스(+10), 혼조장은 페널티(-15)
    if regime in (MarketRegime.TRENDING_UP, MarketRegime.TRENDING_DOWN):
        base_conf += 10
    elif regime == MarketRegime.CHOPPY:
        base_conf -= 15

    return round(min(max(base_conf, 0.0), 100.0), 1)


def _score_to_signal(score: float) -> Signal:
    if score >= 6:
        return Signal.STRONG_BUY
    if score >= 2:
        return Signal.BUY
    if score <= -6:
        return Signal.STRONG_SELL
    if score <= -2:
        return Signal.SELL
    return Signal.HOLD


def generate_report(
    ticker: str,
    price: float,
    change: float,
    change_rate: float,
    daily_candles: list[object],
) -> AnalysisReport:
    """Generate full analysis report from technical indicators."""
    logger.info("generate_report: ticker=%s, price=%.2f, candles=%d", ticker, price, len(daily_candles))
    from app.stock.models import Candle

    candles = [c for c in daily_candles if isinstance(c, Candle)]
    if not candles:
        from datetime import date as date_type
        candles = [Candle(date=date_type.today(), open=price, high=price, low=price, close=price, volume=0)]

    tech_result = analyze(candles)

    # Detect regime first, then pass to technical_score
    regime = _detect_regime(tech_result, price)
    weights = _dynamic_weights(regime)
    tech_scores = technical_score(tech_result, price, regime=regime.value)

    # 가중 기술 분석 총점: 각 지표 점수 × regime 기반 가중치
    tech_total = sum(tech_scores[k] * weights.get(k, 1.0) for k in tech_scores)

    # 거래량 다이버전스 확인: 시그널 방향과 OBV 방향이 불일치하면 신뢰도 하락
    vol_divergence = False
    if tech_total > 0 and tech_result.obv_trend_dir < 0:
        vol_divergence = True
    elif tech_total < 0 and tech_result.obv_trend_dir > 0:
        vol_divergence = True

    total = tech_total

    signal = _score_to_signal(total)
    confidence = _calc_confidence(tech_scores, weights, regime)

    # Volume divergence penalty: OBV 다이버전스 시 신뢰도 25% 차감
    if vol_divergence:
        confidence = round(confidence * 0.75, 1)

    # Signal quality grade: 신뢰도 기준 강도 분류
    if confidence >= 70:
        quality = "strong"
    elif confidence >= 45:
        quality = "moderate"
    else:
        quality = "weak"

    logger.info(
        "generate_report: completed — ticker=%s, signal=%s, score=%.1f, confidence=%.1f, regime=%s, quality=%s",
        ticker, signal.value, total, confidence, regime.value, quality,
    )

    return AnalysisReport(
        ticker=ticker,
        signal=signal,
        total_score=round(total, 1),
        confidence=confidence,
        signal_quality=quality,
        market_regime=regime,
        technical_scores=tech_scores,
        technical_weights=weights,
        price=price,
        change=change,
        change_rate=change_rate,
        technical_details=tech_result,
    )


def format_report(report: AnalysisReport) -> str:
    """Format report as readable string."""
    lines = [
        f"{'='*60}",
        f"  {report.ticker} Analysis Report",
        f"{'='*60}",
        f"",
        f"  Price: ${report.price:.2f}  (Change: {report.change:+.2f} / {report.change_rate:+.2f}%)",
        f"  Signal: {report.signal.value}  (Confidence: {report.confidence:.0f}%)  Quality: {report.signal_quality}",
        f"  Market Regime: {report.market_regime.value}",
        f"  Total Score: {report.total_score:+.1f}",
        f"",
        f"  --- Moving Averages ---",
        f"  SMA5:  ${report.technical_details.ma5:.2f}",
        f"  SMA20: ${report.technical_details.ma20:.2f}",
        f"  EMA12: ${report.technical_details.ema12:.2f}",
        f"  EMA26: ${report.technical_details.ema26:.2f}",
        f"  VWAP:  ${report.technical_details.vwap:.2f}",
        f"",
        f"  --- Oscillators ---",
        f"  RSI(14): {report.technical_details.rsi_14:.1f}",
        f"  Stochastic: K={report.technical_details.stochastic_k:.1f}% / D={report.technical_details.stochastic_d:.1f}%",
        f"",
        f"  --- Trend & Volatility ---",
        f"  MACD: {report.technical_details.macd_line:.2f} / Signal: {report.technical_details.macd_signal:.2f} / Hist: {report.technical_details.macd_histogram:.2f}",
        f"  ADX: {report.technical_details.adx:.1f}",
        f"  ATR: {report.technical_details.atr:.2f}",
        f"",
        f"  --- Bands ---",
        f"  BB: Upper ${report.technical_details.bb_upper:.2f} / Mid ${report.technical_details.bb_middle:.2f} / Lower ${report.technical_details.bb_lower:.2f}",
        f"",
        f"  --- Volume ---",
        f"  OBV: {report.technical_details.obv:,.0f}",
        f"",
        f"  --- Technical Scores ---",
    ]
    for indicator, score in report.technical_scores.items():
        arrow = "+" if score > 0 else ("" if score < 0 else " ")
        w = report.technical_weights.get(indicator, 1.0)
        lines.append(f"  {indicator.upper():12s}: {arrow}{score}  (weight: {w:.1f})")
    lines.append(f"")
    lines.append(f"{'='*60}")
    return "\n".join(lines)
