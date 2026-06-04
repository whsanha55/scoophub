# stock/repository/sigma.py — Repository for stock_sigma table (ATM straddle).
from __future__ import annotations

from datetime import date, datetime
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

from app.stock.models import SigmaResult


def _row_to_sigma(row: dict) -> SigmaResult:
    """Convert a DB row dict to SigmaResult dataclass."""
    expiry = row.get("expiry_date")
    if isinstance(expiry, datetime):
        expiry = expiry.date()

    snap_date = row.get("snapshot_date")
    if isinstance(snap_date, datetime):
        snap_date = snap_date.date()

    snap_at = row.get("snapshot_at")

    pcr = row.get("put_call_volume_ratio")
    if pcr is not None:
        pcr = float(pcr)

    return SigmaResult(
        id=row.get("id"),
        ticker=row["ticker"],
        current_price=float(row.get("current_price", 0)),
        expiry_date=expiry,
        atm_strike=float(row.get("atm_strike", 0)),
        atm_call=float(row.get("atm_call", 0)),
        atm_put=float(row.get("atm_put", 0)),
        expected_move=float(row.get("expected_move", 0)),
        expected_move_pct=float(row.get("expected_move_pct", 0)),
        snapshot_date=snap_date,
        snapshot_at=snap_at,
        source=row.get("source", "yfinance_straddle"),
        total_call_volume=int(row.get("total_call_volume", 0) or 0),
        total_put_volume=int(row.get("total_put_volume", 0) or 0),
        put_call_volume_ratio=pcr,
        atm_call_volume=int(row.get("atm_call_volume", 0) or 0),
        atm_put_volume=int(row.get("atm_put_volume", 0) or 0),
        created_at=row.get("created_at"),
    )


class SigmaRepo:
    def __init__(self, db: Database):
        self._db = db

    async def save(self, sigma: SigmaResult) -> SigmaResult:
        """Upsert a sigma result into stock_sigma."""
        row = await self._db.fetchrow(
            "INSERT INTO stock_sigma "
            "(ticker, expiry_date, snapshot_date, snapshot_at, current_price, "
            " atm_strike, atm_call, atm_put, expected_move, expected_move_pct, "
            " total_call_volume, total_put_volume, put_call_volume_ratio, "
            " atm_call_volume, atm_put_volume) "
            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15) "
            "ON CONFLICT (ticker, expiry_date, snapshot_date) DO UPDATE SET "
            " current_price = EXCLUDED.current_price, "
            " atm_strike = EXCLUDED.atm_strike, "
            " atm_call = EXCLUDED.atm_call, "
            " atm_put = EXCLUDED.atm_put, "
            " expected_move = EXCLUDED.expected_move, "
            " expected_move_pct = EXCLUDED.expected_move_pct, "
            " snapshot_at = EXCLUDED.snapshot_at, "
            " total_call_volume = EXCLUDED.total_call_volume, "
            " total_put_volume = EXCLUDED.total_put_volume, "
            " put_call_volume_ratio = EXCLUDED.put_call_volume_ratio, "
            " atm_call_volume = EXCLUDED.atm_call_volume, "
            " atm_put_volume = EXCLUDED.atm_put_volume, "
            " updated_at = NOW() "
            "RETURNING *",
            sigma.ticker,
            sigma.expiry_date,
            sigma.snapshot_date,
            sigma.snapshot_at,
            sigma.current_price,
            sigma.atm_strike,
            sigma.atm_call,
            sigma.atm_put,
            sigma.expected_move,
            sigma.expected_move_pct,
            sigma.total_call_volume,
            sigma.total_put_volume,
            sigma.put_call_volume_ratio,
            sigma.atm_call_volume,
            sigma.atm_put_volume,
        )
        return _row_to_sigma(row)

    async def get_latest(self, ticker: str) -> SigmaResult | None:
        """Get the most recent sigma for a ticker."""
        row = await self._db.fetchrow(
            "SELECT * FROM stock_sigma "
            "WHERE ticker = $1 "
            "ORDER BY snapshot_date DESC, expiry_date ASC LIMIT 1",
            ticker,
        )
        return _row_to_sigma(row) if row else None

    async def get_all(self, ticker: str, limit: int = 30) -> list[SigmaResult]:
        """Get recent sigma history for a ticker."""
        rows = await self._db.fetch(
            "SELECT * FROM stock_sigma "
            "WHERE ticker = $1 "
            "ORDER BY snapshot_date DESC, expiry_date ASC LIMIT $2",
            ticker,
            limit,
        )
        return [_row_to_sigma(r) for r in rows]
