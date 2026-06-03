# stock/repository.py — asyncpg-based repository implementations.
from __future__ import annotations

import json
from datetime import date
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

from app.stock.models import (
    Candle,
    TickerParams,
    WatchlistItem,
    WeeklyExpectedMove,
)

# ── Row → Domain helpers ─────────────────────────────────────────────


def _row_to_watchlist(row: object) -> WatchlistItem:
    return WatchlistItem(
        id=row["id"],
        ticker=row["ticker"],
        exchange=row["exchange"],
        name=row["name"],
        memo=row["memo"],
        added_at=row["added_at"].date() if hasattr(row["added_at"], "date") else row["added_at"],
        is_active=row["is_active"],
    )


def _row_to_wem(row: object) -> WeeklyExpectedMove:
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


def _row_to_candle(row: object) -> Candle:
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


def _row_to_stock_ticker_params(row: object) -> TickerParams:
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


# ── WatchlistRepo ────────────────────────────────────────────────────

M7_DEFAULTS = [
    ("AAPL", "NAS", "Apple Inc."),
    ("MSFT", "NAS", "Microsoft Corp."),
    ("GOOGL", "NAS", "Alphabet Inc."),
    ("AMZN", "NAS", "Amazon.com Inc."),
    ("NVDA", "NAS", "NVIDIA Corp."),
    ("META", "NAS", "Meta Platforms Inc."),
    ("TSLA", "NAS", "Tesla Inc."),
]


class WatchlistRepo:
    def __init__(self, db: Database):
        self._db = db

    async def find_all(self, active_only: bool = False) -> list[WatchlistItem]:
        if active_only:
            rows = await self._db.fetch(
                "SELECT * FROM stock_watchlist WHERE is_active = TRUE ORDER BY added_at"
            )
        else:
            rows = await self._db.fetch("SELECT * FROM stock_watchlist ORDER BY added_at")
        return [_row_to_watchlist(r) for r in rows]

    async def find_by_id(self, item_id: int) -> WatchlistItem | None:
        row = await self._db.fetchrow("SELECT * FROM stock_watchlist WHERE id = $1", item_id)
        return _row_to_watchlist(row) if row else None

    async def find_by_ticker(self, ticker: str) -> WatchlistItem | None:
        row = await self._db.fetchrow(
            "SELECT * FROM stock_watchlist WHERE ticker = $1 AND is_active = TRUE", ticker
        )
        return _row_to_watchlist(row) if row else None

    async def add(self, item: WatchlistItem) -> WatchlistItem:
        row = await self._db.fetchrow(
            "INSERT INTO stock_watchlist (ticker, exchange, name, memo, is_active) "
            "VALUES ($1, $2, $3, $4, $5) RETURNING *",
            item.ticker.upper(),
            item.exchange.upper(),
            item.name,
            item.memo,
            item.is_active,
        )
        return _row_to_watchlist(row)

    async def update(self, item_id: int, **kwargs) -> WatchlistItem | None:
        existing = await self.find_by_id(item_id)
        if existing is None:
            return None
        sets: list[str] = []
        values: list[object] = []
        idx = 1
        for k, v in kwargs.items():
            if v is not None:
                sets.append(f"{k} = ${idx}")
                values.append(v)
                idx += 1
        if not sets:
            return existing
        values.append(item_id)
        row = await self._db.fetchrow(
            f"UPDATE stock_watchlist SET {', '.join(sets)} WHERE id = ${idx} RETURNING *",
            *values,
        )
        return _row_to_watchlist(row) if row else None

    async def remove(self, item_id: int) -> bool:
        result = await self._db.execute("DELETE FROM stock_watchlist WHERE id = $1", item_id)
        return result == "DELETE 1"

    async def seed_defaults(self) -> int:
        count = await self._db.fetchval("SELECT COUNT(*) FROM stock_watchlist")
        if count and count > 0:
            return 0
        parts: list[str] = []
        params: list[object] = []
        idx = 1
        for ticker, exchange, name in M7_DEFAULTS:
            parts.append(f"(${idx}, ${idx+1}, ${idx+2}, ${idx+3}, ${idx+4})")
            params.extend([ticker, exchange, name, "M7", True])
            idx += 5
        await self._db.execute(
            "INSERT INTO stock_watchlist (ticker, exchange, name, memo, is_active) "
            "VALUES " + ", ".join(parts),
            *params,
        )
        return len(M7_DEFAULTS)


# ── WeeklyExpectedMoveRepo ───────────────────────────────────────────


class WeeklyExpectedMoveRepo:
    def __init__(self, db: Database):
        self._db = db

    async def save(self, wem: WeeklyExpectedMove) -> WeeklyExpectedMove:
        row = await self._db.fetchrow(
            "INSERT INTO stock_weekly_expected_moves "
            "(ticker, week_start, week_end, expected_move_high, expected_move_low, expected_move_pct) "
            "VALUES ($1, $2, $3, $4, $5, $6) "
            "ON CONFLICT (ticker, week_end) DO UPDATE SET "
            "week_start = EXCLUDED.week_start, "
            "expected_move_high = EXCLUDED.expected_move_high, "
            "expected_move_low = EXCLUDED.expected_move_low, "
            "expected_move_pct = EXCLUDED.expected_move_pct, "
            "updated_at = NOW() "
            "RETURNING *",
            wem.ticker,
            wem.week_start,
            wem.week_end,
            wem.expected_move_high,
            wem.expected_move_low,
            wem.expected_move_pct,
        )
        return _row_to_wem(row)

    async def save_batch(self, items: list[WeeklyExpectedMove]) -> int:
        if not items:
            return 0
        # Build batch VALUES with positional params
        parts: list[str] = []
        params: list[object] = []
        idx = 1
        for item in items:
            parts.append(
                f"(${idx}, ${idx+1}, ${idx+2}, ${idx+3}, ${idx+4}, ${idx+5})"
            )
            params.extend([
                item.ticker, item.week_start, item.week_end,
                item.expected_move_high, item.expected_move_low, item.expected_move_pct,
            ])
            idx += 6
        await self._db.execute(
            "INSERT INTO stock_weekly_expected_moves "
            "(ticker, week_start, week_end, expected_move_high, expected_move_low, expected_move_pct) "
            "VALUES " + ", ".join(parts) + " "
            "ON CONFLICT (ticker, week_end) DO UPDATE SET "
            "week_start = EXCLUDED.week_start, "
            "expected_move_high = EXCLUDED.expected_move_high, "
            "expected_move_low = EXCLUDED.expected_move_low, "
            "expected_move_pct = EXCLUDED.expected_move_pct, "
            "updated_at = NOW()",
            *params,
        )
        return len(items)

    async def find_by_ticker(self, ticker: str, limit: int = 52) -> list[WeeklyExpectedMove]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_weekly_expected_moves WHERE ticker = $1 "
            "ORDER BY week_start DESC LIMIT $2",
            ticker,
            limit,
        )
        return [_row_to_wem(r) for r in rows]

    async def find_by_week(self, week_start: date) -> list[WeeklyExpectedMove]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_weekly_expected_moves WHERE week_start = $1",
            week_start,
        )
        return [_row_to_wem(r) for r in rows]

    async def find_all(self, limit: int = 500) -> list[WeeklyExpectedMove]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_weekly_expected_moves ORDER BY week_start DESC LIMIT $1",
            limit,
        )
        return [_row_to_wem(r) for r in rows]


# ── CandleRepo ───────────────────────────────────────────────────────


class CandleRepo:
    def __init__(self, db: Database):
        self._db = db

    async def save_batch(self, items: list[Candle]) -> int:
        if not items:
            return 0
        # Build batch VALUES with positional params
        parts: list[str] = []
        params: list[object] = []
        idx = 1
        for item in items:
            parts.append(
                f"(${idx}, ${idx+1}, ${idx+2}, ${idx+3}, ${idx+4}, ${idx+5}, ${idx+6}, ${idx+7})"
            )
            params.extend([
                item.ticker, item.interval, item.date,
                item.open, item.high, item.low, item.close, item.volume,
            ])
            idx += 8
        await self._db.execute(
            "INSERT INTO stock_candles (ticker, interval, date, open, high, low, close, volume) "
            "VALUES " + ", ".join(parts) + " "
            "ON CONFLICT (ticker, interval, date) DO UPDATE SET "
            "open = EXCLUDED.open, high = EXCLUDED.high, "
            "low = EXCLUDED.low, close = EXCLUDED.close, "
            "volume = EXCLUDED.volume",
            *params,
        )
        return len(items)

    async def find_by_ticker(
        self,
        ticker: str,
        interval: str = "1D",
        start_date: date | None = None,
        end_date: date | None = None,
        limit: int = 5000,
    ) -> list[Candle]:
        query = "SELECT * FROM stock_candles WHERE ticker = $1 AND interval = $2"
        params: list[object] = [ticker, interval]
        idx = 3
        if start_date:
            query += f" AND date >= ${idx}"
            params.append(start_date)
            idx += 1
        if end_date:
            query += f" AND date <= ${idx}"
            params.append(end_date)
            idx += 1
        query += f" ORDER BY date ASC LIMIT ${idx}"
        params.append(limit)
        rows = await self._db.fetch(query, *params)
        return [_row_to_candle(r) for r in rows]

    async def find_latest_date(self, ticker: str, interval: str = "1D") -> date | None:
        return await self._db.fetchval(
            "SELECT date FROM stock_candles WHERE ticker = $1 AND interval = $2 "
            "ORDER BY date DESC LIMIT 1",
            ticker,
            interval,
        )

    async def count(self, ticker: str | None = None, interval: str | None = None) -> int:
        query = "SELECT COUNT(*) FROM stock_candles WHERE TRUE"
        params: list[object] = []
        idx = 1
        if ticker:
            query += f" AND ticker = ${idx}"
            params.append(ticker)
            idx += 1
        if interval:
            query += f" AND interval = ${idx}"
            params.append(interval)
            idx += 1
        result = await self._db.fetchval(query, *params)
        return result or 0


# ── AnalysisResultRepo ───────────────────────────────────────────────


class AnalysisResultRepo:
    def __init__(self, db: Database):
        self._db = db

    async def save(
        self,
        ticker: str,
        exchange: str,
        timeframe: str,
        signal: str,
        total_score: float,
        confidence: float,
        market_regime: str,
        price: float,
        change: float,
        change_rate: float,
        technical_scores: dict,
        technical_details: dict,
    ) -> int:
        row_id = await self._db.fetchval(
            "INSERT INTO stock_analysis_results "
            "(ticker, exchange, timeframe, signal, total_score, confidence, "
            "market_regime, price, change, change_rate, technical_scores, technical_details) "
            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) "
            "ON CONFLICT (ticker, timeframe) DO UPDATE SET "
            "exchange = EXCLUDED.exchange, "
            "signal = EXCLUDED.signal, "
            "total_score = EXCLUDED.total_score, "
            "confidence = EXCLUDED.confidence, "
            "market_regime = EXCLUDED.market_regime, "
            "price = EXCLUDED.price, "
            "change = EXCLUDED.change, "
            "change_rate = EXCLUDED.change_rate, "
            "technical_scores = EXCLUDED.technical_scores, "
            "technical_details = EXCLUDED.technical_details, "
            "analyzed_at = NOW() "
            "RETURNING id",
            ticker,
            exchange,
            timeframe,
            signal,
            total_score,
            confidence,
            market_regime,
            price,
            change,
            change_rate,
            json.dumps(technical_scores),
            json.dumps(technical_details),
        )
        return row_id

    async def find_by_tickers(
        self, tickers: list[str], timeframe: str = "1D"
    ) -> list[dict]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_analysis_results "
            "WHERE ticker = ANY($1) AND timeframe = $2 "
            "ORDER BY analyzed_at DESC",
            tickers,
            timeframe,
        )
        return [dict(r) for r in rows]

    async def find_all(self, timeframe: str = "1D") -> list[dict]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_analysis_results WHERE timeframe = $1 "
            "ORDER BY analyzed_at DESC",
            timeframe,
        )
        return [dict(r) for r in rows]


# ── TickerParamsRepo ─────────────────────────────────────────────────


class TickerParamsRepo:
    def __init__(self, db: Database):
        self._db = db

    async def find_by_ticker(self, ticker: str) -> TickerParams | None:
        row = await self._db.fetchrow(
            "SELECT * FROM stock_ticker_params WHERE ticker = $1", ticker
        )
        return _row_to_stock_ticker_params(row) if row else None

    async def find_all_adopted(self) -> list[TickerParams]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_ticker_params WHERE is_adopted = TRUE"
        )
        return [_row_to_stock_ticker_params(r) for r in rows]

    async def save(self, params: TickerParams) -> TickerParams:
        weights_json = json.dumps(params.weights) if params.weights else None
        row = await self._db.fetchrow(
            "INSERT INTO stock_ticker_params "
            "(ticker, weights, entry_threshold, exit_threshold, position_size_pct, "
            "in_sample_sharpe, in_sample_sortino, out_sample_sharpe, out_sample_sortino, "
            "is_adopted) "
            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) "
            "ON CONFLICT (ticker) DO UPDATE SET "
            "weights = EXCLUDED.weights, "
            "entry_threshold = EXCLUDED.entry_threshold, "
            "exit_threshold = EXCLUDED.exit_threshold, "
            "position_size_pct = EXCLUDED.position_size_pct, "
            "in_sample_sharpe = EXCLUDED.in_sample_sharpe, "
            "in_sample_sortino = EXCLUDED.in_sample_sortino, "
            "out_sample_sharpe = EXCLUDED.out_sample_sharpe, "
            "out_sample_sortino = EXCLUDED.out_sample_sortino, "
            "is_adopted = EXCLUDED.is_adopted "
            "RETURNING *",
            params.ticker,
            weights_json,
            params.entry_threshold,
            params.exit_threshold,
            params.position_size_pct,
            params.in_sample_sharpe,
            params.in_sample_sortino,
            params.out_sample_sharpe,
            params.out_sample_sortino,
            params.is_adopted,
        )
        return _row_to_stock_ticker_params(row)
