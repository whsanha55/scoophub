# stock/repository/_helpers.py — Row → Domain mappers.
from __future__ import annotations

import json

from app.stock.models import (
    Candle,
    TickerParams,
    WatchlistItem,
    WeeklyExpectedMove,
)


def row_to_watchlist(row: object) -> WatchlistItem:
    """DB row -> WatchlistItem 도메인 객체 변환. added_at은 date만 추출."""
    return WatchlistItem(
        id=row["id"],
        ticker=row["ticker"],
        exchange=row["exchange"],
        name=row["name"],
        memo=row["memo"],
        added_at=row["added_at"].date() if hasattr(row["added_at"], "date") else row["added_at"],
        is_active=row["is_active"],
        group=row["group"] if "group" in row.keys() else "individual",
    )


def row_to_wem(row: object) -> WeeklyExpectedMove:
    """DB row -> WeeklyExpectedMove 도메인 객체 변환. created_at/updated_at은 date만 추출."""
    return WeeklyExpectedMove(
        id=row["id"],
        ticker=row["ticker"],
        week_start=row["week_start"],
        week_end=row["week_end"],
        expected_move_high=row["expected_move_high"],
        expected_move_low=row["expected_move_low"],
        expected_move_pct=row["expected_move_pct"],
        created_at=row["created_at"].date() if hasattr(row["created_at"], "date") else row["created_at"],
        updated_at=row["updated_at"].date() if hasattr(row["updated_at"], "date") else row["updated_at"],
    )


def row_to_candle(row: object) -> Candle:
    """DB row -> Candle 도메인 객체 변환. 필드명이 그대로 매핑됨."""
    return Candle(
        ticker=row["ticker"],
        interval=row["interval"],
        date=row["date"],
        open=row["open"],
        high=row["high"],
        low=row["low"],
        close=row["close"],
        volume=row["volume"],
    )


def row_to_stock_ticker_params(row: object) -> TickerParams:
    """DB row -> TickerParams 도메인 객체 변환. weights는 JSON 문자열일 수 있어 파싱 처리."""
    weights = row["weights"]
    if isinstance(weights, str):
        weights = json.loads(weights)
    return TickerParams(
        id=row["id"],
        ticker=row["ticker"],
        weights=weights,
        entry_threshold=row["entry_threshold"],
        exit_threshold=row["exit_threshold"],
        position_size_pct=row["position_size_pct"],
        in_sample_sharpe=row["in_sample_sharpe"],
        in_sample_sortino=row["in_sample_sortino"],
        out_sample_sharpe=row["out_sample_sharpe"],
        out_sample_sortino=row["out_sample_sortino"],
        is_adopted=row["is_adopted"],
        tuned_at=row["tuned_at"].date() if hasattr(row["tuned_at"], "date") else row["tuned_at"],
    )
