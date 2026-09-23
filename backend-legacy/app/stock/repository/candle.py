# stock/repository/candle.py
from __future__ import annotations

import logging
from datetime import date
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

from app.stock.models import Candle
from app.stock.repository._helpers import row_to_candle

logger = logging.getLogger(__name__)


class CandleRepo:
    def __init__(self, db: Database):
        self._db = db

    async def save_batch(self, items: list[Candle]) -> int:
        logger.info("CandleRepo.save_batch() 진입 — count=%d", len(items))
        if not items:
            return 0
        # 다중 INSERT: 8개 컬럼(ticker..volume) VALUES 동적 생성 후
        # (ticker, interval, date) 충돌 시 OHLCV 필드만 갱신
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
        logger.info("CandleRepo.save_batch() 완료 — saved=%d", len(items))
        return len(items)

    async def find_by_ticker(
        self,
        ticker: str,
        interval: str = "1D",
        start_date: date | None = None,
        end_date: date | None = None,
        limit: int = 5000,
    ) -> list[Candle]:
        logger.info("CandleRepo.find_by_ticker() 진입 — ticker=%s, interval=%s, range=%s~%s", ticker, interval, start_date, end_date)
        # 동적 WHERE: start_date/end_date가 있으면 범위 조건 추가
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
        return [row_to_candle(r) for r in rows]

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
