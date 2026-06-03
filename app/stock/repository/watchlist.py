# stock/repository/watchlist.py
from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

from app.stock.models import WatchlistItem
from app.stock.repository._helpers import row_to_watchlist

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
        return [row_to_watchlist(r) for r in rows]

    async def find_by_id(self, item_id: int) -> WatchlistItem | None:
        row = await self._db.fetchrow("SELECT * FROM stock_watchlist WHERE id = $1", item_id)
        return row_to_watchlist(row) if row else None

    async def find_by_ticker(self, ticker: str) -> WatchlistItem | None:
        row = await self._db.fetchrow(
            "SELECT * FROM stock_watchlist WHERE ticker = $1 AND is_active = TRUE", ticker
        )
        return row_to_watchlist(row) if row else None

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
        return row_to_watchlist(row)

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
        return row_to_watchlist(row) if row else None

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
