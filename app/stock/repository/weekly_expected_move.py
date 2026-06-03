# stock/repository/weekly_expected_move.py
from __future__ import annotations

import logging
from datetime import date
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

from app.stock.models import WeeklyExpectedMove
from app.stock.repository._helpers import row_to_wem

logger = logging.getLogger(__name__)


class WeeklyExpectedMoveRepo:
    def __init__(self, db: Database):
        self._db = db

    async def save(self, wem: WeeklyExpectedMove) -> WeeklyExpectedMove:
        logger.info("WeeklyExpectedMoveRepo.save() 진입 — ticker=%s", wem.ticker)
        # UPSERT: (ticker, week_end) 충돌 시 week_start/예상값 갱신 + updated_at 자동 갱신
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
        return row_to_wem(row)

    async def save_batch(self, items: list[WeeklyExpectedMove]) -> int:
        logger.info("WeeklyExpectedMoveRepo.save_batch() 진입 — count=%d", len(items))
        if not items:
            return 0
        # 다중 INSERT: 6개 컬럼 VALUES 동적 생성 후
        # (ticker, week_end) 충돌 시 예상값/updated_at 갱신
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
        logger.info("WeeklyExpectedMoveRepo.save_batch() 완료 — saved=%d", len(items))
        return len(items)

    async def find_by_ticker(self, ticker: str, limit: int = 52) -> list[WeeklyExpectedMove]:
        logger.info("WeeklyExpectedMoveRepo.find_by_ticker() 진입 — ticker=%s, limit=%d", ticker, limit)
        rows = await self._db.fetch(
            "SELECT * FROM stock_weekly_expected_moves WHERE ticker = $1 "
            "ORDER BY week_start DESC LIMIT $2",
            ticker,
            limit,
        )
        return [row_to_wem(r) for r in rows]

    async def find_by_week(self, week_start: date) -> list[WeeklyExpectedMove]:
        rows = await self._db.fetch(
            "SELECT * FROM stock_weekly_expected_moves WHERE week_start = $1",
            week_start,
        )
        return [row_to_wem(r) for r in rows]

    async def find_all(self, limit: int = 500) -> list[WeeklyExpectedMove]:
        logger.info("WeeklyExpectedMoveRepo.find_all() 진입 — limit=%d", limit)
        rows = await self._db.fetch(
            "SELECT * FROM stock_weekly_expected_moves ORDER BY week_start DESC LIMIT $1",
            limit,
        )
        return [row_to_wem(r) for r in rows]
