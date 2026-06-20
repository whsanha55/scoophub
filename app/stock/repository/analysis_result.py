# stock/repository/analysis_result.py
from __future__ import annotations

import json
import logging
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


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
        logger.info("AnalysisResultRepo.save() 진입 — ticker=%s, timeframe=%s", ticker, timeframe)
        # UPSERT: (ticker, timeframe) 충돌 시 전체 필드 갱신 + analyzed_at 갱신
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
        logger.info("AnalysisResultRepo.save() 완료 — ticker=%s, row_id=%s", ticker, row_id)
        return row_id

    async def find_by_tickers(
        self, tickers: list[str], timeframe: str = "1D"
    ) -> list[dict]:
        logger.info("AnalysisResultRepo.find_by_tickers() 진입 — tickers=%s, timeframe=%s", tickers, timeframe)
        # ANY($1) 배열 매칭으로 여러 티커 한 번에 조회
        rows = await self._db.fetch(
            "SELECT * FROM stock_analysis_results "
            "WHERE ticker = ANY($1) AND timeframe = $2 "
            "ORDER BY analyzed_at DESC",
            tickers,
            timeframe,
        )
        return [dict(r) for r in rows]

    async def find_all(self, timeframe: str = "1D") -> list[dict]:
        logger.info("AnalysisResultRepo.find_all() 진입 — timeframe=%s", timeframe)
        rows = await self._db.fetch(
            "SELECT * FROM stock_analysis_results WHERE timeframe = $1 "
            "ORDER BY analyzed_at DESC",
            timeframe,
        )
        return [dict(r) for r in rows]

    async def hit_rate_by_signal(self, horizon_days: int = 5) -> dict[str, dict]:
        """과거 signal → horizon_days 후 수익률 역산 → signal별 히트레이트.

        현재 스키마는 (ticker, timeframe) UNIQUE UPSERT 로 과거 스냅샷을 누적하지 않아
        역산 불가 — 항상 빈 dict(N/A) 반환. 향후 히스토리 테이블 확장 후 구현.
        (T8 Phase 3 인터페이스 — 리포트에서 호출 시 데이터 부족 N/A 정상 처리)
        """
        return {}
