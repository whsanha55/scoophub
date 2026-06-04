# stock/repository/sigma.py — Repository for stock_sigma table.
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
    return SigmaResult(
        id=row.get("id"),
        ticker=row["ticker"],
        current_price=float(row.get("current_price", 0)),
        atm_iv=float(row.get("atm_iv", 0)),
        dte=int(row.get("dte", 0) or 0),
        daily_sigma=float(row.get("daily_sigma", 0)),
        daily_sigma_pct=float(row.get("daily_sigma_pct", 0)),
        expected_move_high=float(row.get("expected_move_high", 0)),
        expected_move_low=float(row.get("expected_move_low", 0)),
        expected_move_pct=float(row.get("expected_move_pct", 0)),
        sigma_type=row.get("sigma_type", "daily"),
        expiry_date=expiry,
        source=row.get("source", "yfinance_options"),
        created_at=row.get("created_at"),
    )


class SigmaRepo:
    def __init__(self, db: Database):
        self._db = db

    async def save(self, sigma: SigmaResult) -> SigmaResult:
        """Upsert a sigma result into stock_sigma."""
        row = await self._db.fetchrow(
            "INSERT INTO stock_sigma "
            "(ticker, sigma_type, current_price, atm_iv, expiry_date, dte, "
            " daily_sigma, daily_sigma_pct, expected_move_high, expected_move_low, expected_move_pct) "
            "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) "
            "ON CONFLICT (ticker, sigma_type, COALESCE(expiry_date, '1970-01-01'::date)) DO UPDATE SET "
            " current_price = EXCLUDED.current_price, "
            " atm_iv = EXCLUDED.atm_iv, "
            " dte = EXCLUDED.dte, "
            " daily_sigma = EXCLUDED.daily_sigma, "
            " daily_sigma_pct = EXCLUDED.daily_sigma_pct, "
            " expected_move_high = EXCLUDED.expected_move_high, "
            " expected_move_low = EXCLUDED.expected_move_low, "
            " expected_move_pct = EXCLUDED.expected_move_pct, "
            " updated_at = NOW() "
            "RETURNING *",
            sigma.ticker,
            sigma.sigma_type,
            sigma.current_price,
            sigma.atm_iv,
            sigma.expiry_date,
            sigma.dte,
            sigma.daily_sigma,
            sigma.daily_sigma_pct,
            sigma.expected_move_high,
            sigma.expected_move_low,
            sigma.expected_move_pct,
        )
        return _row_to_sigma(row)

    async def get_latest(self, ticker: str, sigma_type: str = "daily") -> SigmaResult | None:
        """Get the most recent sigma for a ticker + type."""
        row = await self._db.fetchrow(
            "SELECT * FROM stock_sigma "
            "WHERE ticker = $1 AND sigma_type = $2 "
            "ORDER BY created_at DESC LIMIT 1",
            ticker,
            sigma_type,
        )
        return _row_to_sigma(row) if row else None

    async def get_all(self, ticker: str, sigma_type: str = "daily", limit: int = 30) -> list[SigmaResult]:
        """Get recent sigma history for a ticker."""
        rows = await self._db.fetch(
            "SELECT * FROM stock_sigma "
            "WHERE ticker = $1 AND sigma_type = $2 "
            "ORDER BY created_at DESC LIMIT $3",
            ticker,
            sigma_type,
            limit,
        )
        return [_row_to_sigma(r) for r in rows]
