# stock/repository/ticker_params.py
from __future__ import annotations

import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

from app.stock.models import TickerParams
from app.stock.repository._helpers import row_to_stock_ticker_params


class TickerParamsRepo:
    def __init__(self, db: Database):
        self._db = db

    async def find_by_ticker(self, ticker: str) -> TickerParams | None:
        row = await self._db.fetchrow(
            "SELECT * FROM stock_ticker_params WHERE ticker = $1", ticker
        )
        return row_to_stock_ticker_params(row) if row else None

    async def find_all_adopted(self) -> list[TickerParams]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_ticker_params WHERE is_adopted = TRUE"
        )
        return [row_to_stock_ticker_params(r) for r in rows]

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
        return row_to_stock_ticker_params(row)
