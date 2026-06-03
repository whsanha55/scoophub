# stock/repository/analysis_result.py
from __future__ import annotations

import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database


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
