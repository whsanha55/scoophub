"""Technical analysis indicators for overseas daily candles."""

from __future__ import annotations

from dataclasses import dataclass

from app.stock.models import Candle


@dataclass
class TechnicalResult:
    ma5: float
    ma20: float
    ema12: float
    ema26: float
    rsi_14: float
    macd_line: float
    macd_signal: float
    macd_histogram: float
    bb_upper: float
    bb_middle: float
    bb_lower: float
    stochastic_k: float
    stochastic_d: float
    adx: float
    atr: float
    obv: float
    vwap: float
    bb_width: float = 0.0
    bb_pct_b: float = 0.5
    obv_trend_dir: int = 0


# ---------------------------------------------------------------------------
# Moving averages
# ---------------------------------------------------------------------------

def moving_average(closes: list[float], period: int) -> float:
    if len(closes) < period:
        return 0.0
    return sum(closes[-period:]) / period


def ema(closes: list[float], period: int) -> float:
    if not closes:
        return 0.0
    k = 2.0 / (period + 1)
    result = closes[0]
    for price in closes[1:]:
        result = price * k + result * (1 - k)
    return result


def wma(closes: list[float], period: int) -> float:
    if len(closes) < period:
        return 0.0
    window = closes[-period:]
    weight_sum = period * (period + 1) / 2
    return sum(w * c for w, c in enumerate(window, start=1)) / weight_sum


# ---------------------------------------------------------------------------
# RSI — Wilder smoothing (EWMA)
# ---------------------------------------------------------------------------

def rsi(closes: list[float], period: int = 14) -> float:
    if len(closes) < period + 1:
        return 50.0

    deltas = [closes[i] - closes[i - 1] for i in range(1, len(closes))]
    gains = [max(d, 0.0) for d in deltas]
    losses = [max(-d, 0.0) for d in deltas]

    # Initial average from first `period` values
    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period

    # Wilder smoothing for remaining values
    for i in range(period, len(gains)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period

    if avg_loss == 0:
        return 100.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


# ---------------------------------------------------------------------------
# MACD
# ---------------------------------------------------------------------------

def macd(
    closes: list[float], fast: int = 12, slow: int = 26, signal: int = 9
) -> tuple[float, float, float]:
    if len(closes) < slow:
        return 0.0, 0.0, 0.0

    # Build MACD line series using incremental EMA
    ema_fast = _ema_series(closes, fast)
    ema_slow = _ema_series(closes, slow)
    macd_series = [f - s for f, s in zip(ema_fast, ema_slow)]

    # Signal line = EMA of MACD series
    signal_line = ema(macd_series, signal) if len(macd_series) >= signal else 0.0
    macd_line = macd_series[-1]
    histogram = macd_line - signal_line
    return macd_line, signal_line, histogram


def _ema_series(data: list[float], period: int) -> list[float]:
    """Return full EMA series aligned to input data."""
    if len(data) < period:
        return [0.0] * len(data)
    k = 2.0 / (period + 1)
    result = [0.0] * len(data)
    # Seed with SMA of first `period` values
    result[period - 1] = sum(data[:period]) / period
    for i in range(period, len(data)):
        result[i] = data[i] * k + result[i - 1] * (1 - k)
    return result


# ---------------------------------------------------------------------------
# Bollinger Bands
# ---------------------------------------------------------------------------

def bollinger_bands(
    closes: list[float], period: int = 20, std_mult: float = 2.0
) -> tuple[float, float, float]:
    if len(closes) < period:
        last = closes[-1] if closes else 0.0
        return last, last, last
    window = closes[-period:]
    middle = sum(window) / period
    variance = sum((x - middle) ** 2 for x in window) / period
    std = variance**0.5
    return middle + std_mult * std, middle, middle - std_mult * std


# ---------------------------------------------------------------------------
# Stochastic Oscillator
# ---------------------------------------------------------------------------

def stochastic(
    candles: list[Candle],
    k_period: int = 14,
    d_period: int = 3,
    smooth_period: int = 1,
) -> tuple[float, float]:
    """Return %K and %D values.

    smooth_period=1 → Fast Stochastic (default).
    smooth_period=3 → Slow Stochastic: %K is SMA of fast %K over 3 periods,
                       %D is SMA of slow %K over d_period.
    """
    min_candles = k_period + smooth_period - 1
    if len(candles) < min_candles:
        return 50.0, 50.0

    # Raw (fast) %K series
    fast_k: list[float] = []
    for i in range(k_period - 1, len(candles)):
        window = candles[i - k_period + 1 : i + 1]
        highest = max(c.high for c in window)
        lowest = min(c.low for c in window)
        diff = highest - lowest
        if diff == 0:
            fast_k.append(50.0)
        else:
            fast_k.append((candles[i].close - lowest) / diff * 100)

    # Smooth to get final %K (SMA of fast %K over smooth_period)
    if smooth_period <= 1 or len(fast_k) < smooth_period:
        k_values = fast_k
    else:
        k_values = [
            sum(fast_k[i - smooth_period + 1 : i + 1]) / smooth_period
            for i in range(smooth_period - 1, len(fast_k))
        ]

    k = k_values[-1]
    d = sum(k_values[-d_period:]) / d_period if len(k_values) >= d_period else k
    return k, d


# ---------------------------------------------------------------------------
# ADX — Average Directional Index
# ---------------------------------------------------------------------------

def adx(candles: list[Candle], period: int = 14) -> float:
    if len(candles) < period * 2 + 1:
        return 25.0

    # True Range and directional movement
    tr_list: list[float] = []
    plus_dm: list[float] = []
    minus_dm: list[float] = []

    for i in range(1, len(candles)):
        c = candles[i]
        p = candles[i - 1]

        tr = max(
            c.high - c.low,
            abs(c.high - p.close),
            abs(c.low - p.close),
        )
        tr_list.append(tr)

        up = c.high - p.high
        down = p.low - c.low
        plus_dm.append(up if up > down and up > 0 else 0.0)
        minus_dm.append(down if down > up and down > 0 else 0.0)

    if len(tr_list) < period:
        return 25.0

    # Wilder smoothing initialization
    atr_val = sum(tr_list[:period]) / period
    smooth_plus = sum(plus_dm[:period]) / period
    smooth_minus = sum(minus_dm[:period]) / period

    dx_list: list[float] = []
    for i in range(period, len(tr_list)):
        atr_val = (atr_val * (period - 1) + tr_list[i]) / period
        smooth_plus = (smooth_plus * (period - 1) + plus_dm[i]) / period
        smooth_minus = (smooth_minus * (period - 1) + minus_dm[i]) / period

        atr_val_safe = atr_val if atr_val != 0 else 1e-10
        plus_di = smooth_plus / atr_val_safe * 100
        minus_di = smooth_minus / atr_val_safe * 100
        di_sum = plus_di + minus_di
        dx_list.append(abs(plus_di - minus_di) / di_sum * 100 if di_sum != 0 else 0.0)

    if len(dx_list) < period:
        return dx_list[-1] if dx_list else 25.0

    adx_val = sum(dx_list[:period]) / period
    for i in range(period, len(dx_list)):
        adx_val = (adx_val * (period - 1) + dx_list[i]) / period
    return adx_val


# ---------------------------------------------------------------------------
# ATR — Average True Range
# ---------------------------------------------------------------------------

def atr(candles: list[Candle], period: int = 14) -> float:
    if len(candles) < period + 1:
        return 0.0

    tr_list: list[float] = []
    for i in range(1, len(candles)):
        c = candles[i]
        p = candles[i - 1]
        tr = max(
            c.high - c.low,
            abs(c.high - p.close),
            abs(c.low - p.close),
        )
        tr_list.append(tr)

    # Wilder smoothing
    atr_val = sum(tr_list[:period]) / period
    for i in range(period, len(tr_list)):
        atr_val = (atr_val * (period - 1) + tr_list[i]) / period
    return atr_val


# ---------------------------------------------------------------------------
# OBV — On-Balance Volume
# ---------------------------------------------------------------------------

def obv(candles: list[Candle]) -> float:
    if not candles:
        return 0.0
    total = 0.0
    for i in range(1, len(candles)):
        if candles[i].close > candles[i - 1].close:
            total += candles[i].volume
        elif candles[i].close < candles[i - 1].close:
            total -= candles[i].volume
    return total


# ---------------------------------------------------------------------------
# VWAP — Volume Weighted Average Price
# ---------------------------------------------------------------------------

def vwap(candles: list[Candle], period: int = 20) -> float:
    window = candles[-period:] if len(candles) >= period else candles
    if not window:
        return 0.0
    cum_vol = sum(c.volume for c in window)
    if cum_vol == 0:
        return candles[-1].close if candles else 0.0
    cum_tp_vol = sum((c.high + c.low + c.close) / 3 * c.volume for c in window)
    return cum_tp_vol / cum_vol


# ---------------------------------------------------------------------------
# OBV trend direction
# ---------------------------------------------------------------------------

def obv_trend_direction(candles: list[Candle], period: int = 10) -> int:
    """OBV trend direction: +1 rising, -1 falling, 0 flat."""
    if len(candles) < period + 1:
        return 0

    obv_series: list[float] = []
    total = 0.0
    for i in range(1, len(candles)):
        if candles[i].close > candles[i - 1].close:
            total += candles[i].volume
        elif candles[i].close < candles[i - 1].close:
            total -= candles[i].volume
        obv_series.append(total)

    if len(obv_series) < period:
        return 0

    recent = obv_series[-period:]
    return 1 if recent[-1] > recent[0] else (-1 if recent[-1] < recent[0] else 0)


# ---------------------------------------------------------------------------
# Aggregate analysis
# ---------------------------------------------------------------------------

def analyze(candles: list[Candle]) -> TechnicalResult:
    closes = [c.close for c in candles]

    ma5 = moving_average(closes, 5)
    ma20 = moving_average(closes, 20)
    ema12_val = ema(closes, 12)
    ema26_val = ema(closes, 26)
    rsi_val = rsi(closes, 14)
    macd_line, macd_signal, macd_hist = macd(closes)
    bb_up, bb_mid, bb_low = bollinger_bands(closes)
    stoch_k, stoch_d = stochastic(candles)
    adx_val = adx(candles)
    atr_val = atr(candles)
    obv_val = obv(candles)
    vwap_val = vwap(candles)

    # BB width and %B
    bb_range = bb_up - bb_low
    bb_width = bb_range / bb_mid if bb_mid > 0 else 0.0
    bb_pct_b = (closes[-1] - bb_low) / bb_range if bb_range > 0 else 0.5

    # OBV trend direction
    obv_dir = obv_trend_direction(candles)

    return TechnicalResult(
        ma5=ma5,
        ma20=ma20,
        ema12=ema12_val,
        ema26=ema26_val,
        rsi_14=rsi_val,
        macd_line=macd_line,
        macd_signal=macd_signal,
        macd_histogram=macd_hist,
        bb_upper=bb_up,
        bb_middle=bb_mid,
        bb_lower=bb_low,
        stochastic_k=stoch_k,
        stochastic_d=stoch_d,
        adx=adx_val,
        atr=atr_val,
        obv=obv_val,
        vwap=vwap_val,
        bb_width=bb_width,
        bb_pct_b=bb_pct_b,
        obv_trend_dir=obv_dir,
    )


def technical_score(
    result: TechnicalResult, current_price: float, *, regime: str | None = None,
) -> dict[str, int]:
    """Return per-indicator scores (-2 to +2).

    Args:
        result: Technical analysis result.
        current_price: Current asset price.
        regime: Market regime for context-aware scoring.
                "TRENDING_UP", "TRENDING_DOWN", "RANGING", "CHOPPY".
                None = regime-neutral (backward compatible).
    """
    scores: dict[str, int] = {}

    is_up = regime in ("TRENDING_UP", "TRENDING")
    is_down = regime == "TRENDING_DOWN"
    is_ranging = regime in ("RANGING", "CHOPPY")

    # MA: price above both MAs = bullish
    if current_price > result.ma5 and current_price > result.ma20:
        scores["ma"] = 2
    elif current_price > result.ma5:
        scores["ma"] = 1
    elif current_price < result.ma5 and current_price < result.ma20:
        scores["ma"] = -2
    else:
        scores["ma"] = -1

    # RSI — regime-aware thresholds
    if is_up:
        if result.rsi_14 < 30:
            scores["rsi"] = 2
        elif result.rsi_14 < 40:
            scores["rsi"] = 1
        elif result.rsi_14 > 70:
            scores["rsi"] = 0
        elif result.rsi_14 > 60:
            scores["rsi"] = 1
        else:
            scores["rsi"] = 0
    elif is_down:
        if result.rsi_14 > 70:
            scores["rsi"] = -2
        elif result.rsi_14 > 60:
            scores["rsi"] = -1
        elif result.rsi_14 < 30:
            scores["rsi"] = 0
        elif result.rsi_14 < 40:
            scores["rsi"] = -1
        else:
            scores["rsi"] = 0
    else:
        if result.rsi_14 < 30:
            scores["rsi"] = 2
        elif result.rsi_14 < 40:
            scores["rsi"] = 1
        elif result.rsi_14 > 70:
            scores["rsi"] = -2
        elif result.rsi_14 > 60:
            scores["rsi"] = -1
        else:
            scores["rsi"] = 0

    # MACD
    if result.macd_histogram > 0 and result.macd_line > result.macd_signal:
        scores["macd"] = 2
    elif result.macd_histogram > 0:
        scores["macd"] = 1
    elif result.macd_histogram < 0 and result.macd_line < result.macd_signal:
        scores["macd"] = -2
    else:
        scores["macd"] = -1

    # Bollinger Bands — use %B for position, width for squeeze context
    pct_b = result.bb_pct_b
    is_squeeze = result.bb_width < 0.04  # narrow bands → pending breakout

    if pct_b < 0.0:
        scores["bb"] = 2
    elif pct_b < 0.2:
        scores["bb"] = 1
    elif pct_b > 1.0:
        scores["bb"] = -2
    elif pct_b > 0.8:
        scores["bb"] = -1
    elif is_squeeze and is_up and pct_b > 0.5:
        scores["bb"] = 1
    elif is_squeeze and is_down and pct_b < 0.5:
        scores["bb"] = -1
    else:
        scores["bb"] = 0

    # Stochastic
    if result.stochastic_k < 20:
        scores["stochastic"] = 2
    elif result.stochastic_k < 30:
        scores["stochastic"] = 1
    elif result.stochastic_k > 80:
        scores["stochastic"] = -2
    elif result.stochastic_k > 70:
        scores["stochastic"] = -1
    else:
        scores["stochastic"] = 0

    # ADX — trend strength as directional confirmation
    if result.adx > 25:
        scores["adx"] = 1 if current_price > result.ema12 else -1
    else:
        scores["adx"] = 0

    # VWAP — price relative to VWAP
    if current_price > result.vwap * 1.02:
        scores["vwap"] = 2
    elif current_price > result.vwap:
        scores["vwap"] = 1
    elif current_price < result.vwap * 0.98:
        scores["vwap"] = -2
    elif current_price < result.vwap:
        scores["vwap"] = -1
    else:
        scores["vwap"] = 0

    return scores
