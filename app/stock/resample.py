# stock/resample.py — 1D 캔들 → 주/월 캔들 resample (다중 기간 분석용).
"""OHLCV 정확 집계 규칙:
  - open  = 첫 캔들의 open
  - high  = 구간 최대 high
  - low   = 구간 최소 low
  - close = 마지막 캔들의 close
  - volume= 구간 거래량 합

그룹 경계:
  - 주간: 월요일 시작(ISO week, W-MON).
  - 월간: 자연월(해당 연/월).

최소 캔들 가드: resample 후 캔들 수가 분석에 부족하면 빈 리스트 반환.
"""
from __future__ import annotations

import logging
from datetime import date, timedelta

from app.stock.models import Candle

logger = logging.getLogger(__name__)

# 분석 루프에서 의미 있는 지표를 계산하려면 최소 이 정도의 resample 캔들이 필요.
# (MA20, ADX period*2 등). 부족 시 가짜 분석 영속화 방지를 위해 스킵.
MIN_WEEKLY_CANDLES = 26
MIN_MONTHLY_CANDLES = 20


def _monday_of(d: date) -> date:
    """해당 날짜가 속한 주의 월요일(ISO 주 시작) 반환."""
    # isoweekday(): 월=1 .. 일=7
    return d - timedelta(days=d.isoweekday() - 1)


def _month_key(d: date) -> tuple[int, int]:
    return (d.year, d.month)


def _aggregate(group: list[Candle], rule_label: str) -> Candle:
    first = group[0]
    last = group[-1]
    return Candle(
        date=first.date,
        open=first.open,
        high=max(c.high for c in group),
        low=min(c.low for c in group),
        close=last.close,
        volume=sum(c.volume for c in group),
        ticker=first.ticker,
        interval=rule_label,
    )


def resample_weekly(daily: list[Candle]) -> list[Candle]:
    """1D 캔들 → 주봉(W-MON). 빈 입력/부족 시 빈 리스트."""
    if not daily:
        return []
    sorted_daily = sorted(daily, key=lambda c: c.date)
    buckets: dict[date, list[Candle]] = {}
    for c in sorted_daily:
        buckets.setdefault(_monday_of(c.date), []).append(c)
    # 각 버킷은 이미 날짜순 정렬(정렬된 입력 순회). 안전하게 한 번 더 보장.
    weekly = [_aggregate(g, "1W") for _, g in sorted(buckets.items())]
    if len(weekly) < MIN_WEEKLY_CANDLES:
        logger.info(
            "resample_weekly: insufficient weekly candles (%d < %d) — skip",
            len(weekly), MIN_WEEKLY_CANDLES,
        )
        return []
    return weekly


def resample_monthly(daily: list[Candle]) -> list[Candle]:
    """1D 캔들 → 월봉(자연월). 빈 입력/부족 시 빈 리스트."""
    if not daily:
        return []
    sorted_daily = sorted(daily, key=lambda c: c.date)
    buckets: dict[tuple[int, int], list[Candle]] = {}
    for c in sorted_daily:
        buckets.setdefault(_month_key(c.date), []).append(c)
    # 버킷 키 순서 = 시간순 보장을 위해 첫 캔들 날짜 기준 정렬
    ordered = sorted(buckets.items(), key=lambda kv: kv[1][0].date)
    monthly = [_aggregate(g, "1M") for _, g in ordered]
    if len(monthly) < MIN_MONTHLY_CANDLES:
        logger.info(
            "resample_monthly: insufficient monthly candles (%d < %d) — skip",
            len(monthly), MIN_MONTHLY_CANDLES,
        )
        return []
    return monthly
