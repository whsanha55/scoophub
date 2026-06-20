# tests/test_stock_resample.py — T2: 1D → 주/월 resample 정합성.
"""resample 규칙: open=first, high=max, low=min, close=last, volume=sum.
경계: 주간=월요일 시작(W-MON), 월간=자연월.
최소 캔들 수 미달 시 빈 리스트(가짜 분석 영속화 방지).
"""
from datetime import date, timedelta

from app.stock.models import Candle
from app.stock.resample import resample_weekly, resample_monthly


def _d(start: date, offset: int) -> date:
    return start + timedelta(days=offset)


def _c(d: date, o: float, h: float, l: float, c: float, v: float) -> Candle:
    return Candle(date=d, open=o, high=h, low=l, close=c, volume=v, ticker="TST", interval="1D")


def test_resample_weekly_aggregation_correctness():
    # 2024-01-01(월) ~ 2024-01-05(금) 한 주 + 충분한 주들.
    # 매일 변동하는 OHLCV로 open=first/close=last/high=max/low=min/volume=sum 검증.
    base = date(2024, 1, 1)  # 월요일
    daily = []
    # 40주 분량의 평일(월-금) 생성 → 주봉 40개(>= MIN_WEEKLY_CANDLES=26)
    for week in range(40):
        for day in range(5):  # 월~금
            d = base + timedelta(days=week * 7 + day)
            daily.append(_c(d, o=100 + day, h=105 + day, l=95 + day, c=102 + day, v=1000 + day))
    weekly = resample_weekly(daily)
    assert len(weekly) == 40
    first = weekly[0]
    # 첫 주: open=월(100), close=금(106), high=금(109), low=월(95), vol=sum(1000..1004)=5010
    assert first.date == date(2024, 1, 1)
    assert first.open == 100  # 월요일 open
    assert first.close == 106  # 금요일 close (102+4)
    assert first.high == 109  # 금요일 high (105+4)
    assert first.low == 95  # 월요일 low
    assert first.volume == 1000 + 1001 + 1002 + 1003 + 1004
    assert first.interval == "1W"


def test_resample_weekly_monday_boundary():
    # 일요일(주말) 캔들이 전 주로 묶이지 않는지: 금-토-일-월 시퀀스에서
    # 토/일은 같은 주(월요일 시작)에 속하지만 다음 주 월요일은 새 버킷.
    # 2024-01-06(토), 01-07(일) → 같은 01-01(월) 주. 01-08(월) → 새 주.
    base = date(2024, 1, 1)
    daily = []
    for week in range(30):
        for day in range(7):  # 일주일 전체(월-일)
            d = base + timedelta(days=week * 7 + day)
            daily.append(_c(d, 100, 101, 99, 100, 10))
    weekly = resample_weekly(daily)
    # 각 주는 월~일(7일) → 30주 = 30 주봉
    assert len(weekly) == 30
    assert weekly[0].date == date(2024, 1, 1)
    assert weekly[1].date == date(2024, 1, 8)  # 다음 주 월요일


def test_resample_weekly_insufficient_returns_empty():
    # 5주치 평일 → 주봉 5개(< 26) → 빈 리스트(분석 스킵)
    base = date(2024, 1, 1)
    daily = []
    for week in range(5):
        for day in range(5):
            daily.append(_c(base + timedelta(days=week * 7 + day), 100, 101, 99, 100, 10))
    assert resample_weekly(daily) == []


def test_resample_weekly_empty_returns_empty():
    assert resample_weekly([]) == []


def test_resample_monthly_aggregation_correctness():
    # 자연월 경계. 2024-01 평일 전체가 한 달봉으로 집계되는지.
    base = date(2024, 1, 1)
    daily = []
    # 24개월 생성 (각월 평일) → 월봉 24개(< 20 아님, MIN_MONTHLY=20 통과)
    for month_offset in range(24):
        year = 2024 + (month_offset // 12)
        month = (month_offset % 12) + 1
        # 해당월의 모든 날(1~28) — open/close 모두 day에 비례해 변동
        for day in range(1, 29):
            daily.append(_c(date(year, month, day), 200 + day, 210, 190, 200 + day, 50))
    monthly = resample_monthly(daily)
    assert len(monthly) == 24
    first = monthly[0]
    assert first.date == date(2024, 1, 1)
    assert first.open == 201  # 1일 open (200+1)
    assert first.close == 228  # 28일 close (200+28)
    assert first.high == 210
    assert first.low == 190
    assert first.volume == 50 * 28
    assert first.interval == "1M"


def test_resample_monthly_natural_month_boundary():
    # 1월 31일과 2월 1일이 서로 다른 월봉에 속하는지.
    daily = []
    for month_offset in range(22):
        year = 2024 + (month_offset // 12)
        month = (month_offset % 12) + 1
        for day in range(1, 29):
            daily.append(_c(date(year, month, day), 100, 101, 99, 100, 10))
    monthly = resample_monthly(daily)
    assert monthly[0].date == date(2024, 1, 1)
    assert monthly[1].date == date(2024, 2, 1)


def test_resample_monthly_insufficient_returns_empty():
    daily = [_c(date(2024, 1, d), 100, 101, 99, 100, 10) for d in range(1, 29)]
    assert resample_monthly(daily) == []  # 1개월 < 20
