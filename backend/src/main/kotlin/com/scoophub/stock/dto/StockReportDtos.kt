package com.scoophub.stock.dto

import com.scoophub.stock.ActionableLevels
import com.scoophub.stock.StockSigma
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockSigmaEntity
import tools.jackson.databind.JsonNode
import java.time.Instant

/** legacy `schemas.py` TechnicalOut */
data class TechnicalOut(
    val signal: String,
    val totalScore: Double,
    val confidence: Double,
    val marketRegime: String,
    val technicalScores: JsonNode,
    val technicalDetails: JsonNode,
)

/** legacy `schemas.py` ActionableLevelsOut */
data class ActionableLevelsOut(
    val targetPrice: Double? = null,
    val buyZone: Double? = null,
    val stopLoss: Double? = null,
    val momentumFire: Boolean = false,
) {
    companion object {
        fun from(levels: ActionableLevels) = ActionableLevelsOut(
            targetPrice = levels.targetPrice,
            buyZone = levels.buyZone,
            stopLoss = levels.stopLoss,
            momentumFire = levels.momentumFire,
        )
    }
}

/** legacy `schemas.py` StockQuoteOut — /stock/detail 실시간 시세 */
data class StockQuoteOut(
    val price: Double,
    val change: Double,
    val changeRate: Double,
    val volume: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val open: Double? = null,
    val source: String = "alpaca",
    val timestamp: Instant? = null,
)

/** legacy `schemas.py` StockReport — actionable_levels/group/quote enrichment 로 var */
data class StockReport(
    val ticker: String,
    val exchange: String,
    val price: Double,
    val change: Double,
    val changeRate: Double,
    val technical: TechnicalOut,
    var actionableLevels: ActionableLevelsOut? = null,
    val hitRate: Double? = null,
    var group: String? = null,
    val dataDate: Instant? = null,
    val isStale: Boolean? = null,
    var quote: StockQuoteOut? = null,
)

/** legacy `schemas.py` StockSummary — /stock/report/all summarize=true 요약 */
data class StockSummary(
    val ticker: String,
    val exchange: String,
    val price: Double,
    val change: Double,
    val changeRate: Double,
    val signal: String,
    val totalScore: Double,
    val confidence: Double,
    val marketRegime: String,
    val actionableLevels: ActionableLevelsOut? = null,
    val hitRate: Double? = null,
    val group: String? = null,
    val dataDate: Instant? = null,
    val isStale: Boolean? = null,
) {
    companion object {
        fun from(report: StockReport, row: StockAnalysisResultEntity) = StockSummary(
            ticker = report.ticker,
            exchange = report.exchange,
            price = report.price,
            change = report.change,
            changeRate = report.changeRate,
            signal = row.signal,
            totalScore = row.totalScore,
            confidence = row.confidence,
            marketRegime = row.marketRegime,
            actionableLevels = report.actionableLevels,
            group = report.group,
            dataDate = report.dataDate,
            isStale = report.isStale,
        )
    }
}

/** legacy `schemas.py` SigmaDataOut — stock_sigma 최신 스냅샷 */
data class SigmaSnapshotOut(
    val ticker: String,
    val currentPrice: Double,
    val expiryDate: String? = null,
    val atmStrike: Double,
    val atmCall: Double,
    val atmPut: Double,
    val expectedMove: Double,
    val expectedMovePct: Double,
    val snapshotDate: String? = null,
    val snapshotAt: Instant? = null,
    val source: String,
    val totalCallVolume: Long,
    val totalPutVolume: Long,
    val putCallVolumeRatio: Double? = null,
    val atmCallVolume: Long,
    val atmPutVolume: Long,
    val createdAt: Instant? = null,
) {
    companion object {
        /** stock_sigma 테이블에 source 컬럼 없음 — legacy `_row_to_sigma` 기본값 */
        fun from(row: StockSigmaEntity) = SigmaSnapshotOut(
            ticker = row.ticker,
            currentPrice = row.currentPrice,
            expiryDate = row.expiryDate.toString(),
            atmStrike = row.atmStrike,
            atmCall = row.atmCall,
            atmPut = row.atmPut,
            expectedMove = row.expectedMove,
            expectedMovePct = row.expectedMovePct,
            snapshotDate = row.snapshotDate.toString(),
            snapshotAt = row.snapshotAt,
            source = StockSigma.SOURCE,
            totalCallVolume = row.totalCallVolume,
            totalPutVolume = row.totalPutVolume,
            putCallVolumeRatio = row.putCallVolumeRatio,
            atmCallVolume = row.atmCallVolume,
            atmPutVolume = row.atmPutVolume,
            createdAt = row.createdAt,
        )
    }
}

/** legacy market_status 응답 data dict */
data class MarketStatusOut(val isOpen: Boolean, val isWeekday: Boolean, val currentUtc: Instant)
