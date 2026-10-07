package com.scoophub.stock.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.scoophub.global.jackson.scalar
import com.scoophub.stock.ActionableLevels
import com.scoophub.stock.StockSigma
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockSigmaEntity
import com.scoophub.stock.entity.StockWeeklyExpectedMoveEntity
import com.scoophub.stock.vo.SigmaModel
import com.scoophub.stock.vo.WeeklyExpectedMove
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

/**
 * legacy `schemas.py` SigmaOut. NON_NULL — legacy 응답 형식 유지:
 * 저장 스냅샷 경로(생성자 검증)는 `source` 키가 제거되고 `weekly_moves=[]`,
 * WEM 폴백 경우(대입)는 원본 dict(source + weekly_moves) 그대로 직렬화된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SigmaOut(
    val sigmaPosition: String,
    val sigmaSignal: String,
    val sigmaConfidence: Double,
    val expectedMovePct: Double,
    val expectedMoveHigh: Double,
    val expectedMoveLow: Double,
    val source: String? = null,
    val weeklyMoves: List<WeeklyMoveOut> = emptyList(),
) {
    companion object {
        /** `_analysis_row_to_report` — technical_details.sigma_data.weekly_expected_move 저장 스냅샷 */
        fun fromPersistedSnapshot(techDetails: JsonNode?): SigmaOut? {
            val wem = techDetails?.get("sigma_data")?.get("weekly_expected_move")?.takeIf { !it.isNull }
                ?: return null
            return SigmaOut(
                sigmaPosition = wem.scalar("sigma_position") ?: "",
                sigmaSignal = wem.scalar("sigma_signal") ?: "",
                sigmaConfidence = wem.scalar("sigma_confidence")?.toDouble() ?: 0.0,
                expectedMovePct = wem.scalar("expected_move_pct")?.toDouble() ?: 0.0,
                expectedMoveHigh = wem.scalar("expected_move_high")?.toDouble() ?: 0.0,
                expectedMoveLow = wem.scalar("expected_move_low")?.toDouble() ?: 0.0,
            )
        }

        /** `_enrich_sigma_fallback` — WEM 최근 내역으로 실시간 산출 */
        fun fromWem(wemList: List<StockWeeklyExpectedMoveEntity>, price: Double): SigmaOut {
            val first = wemList.first()
            val signal = SigmaModel.generateSigmaSignal(SigmaModel.computeSigmaRange(first.toVo(), price))
            return SigmaOut(
                sigmaPosition = signal.sigmaPosition.name,
                sigmaSignal = signal.signal.name,
                sigmaConfidence = signal.confidence,
                expectedMovePct = first.expectedMovePct,
                expectedMoveHigh = first.expectedMoveHigh,
                expectedMoveLow = first.expectedMoveLow,
                source = "usstocksigma_html",
                weeklyMoves = wemList.map {
                    WeeklyMoveOut(
                        weekStart = it.weekStart.toString(),
                        weekEnd = it.weekEnd.toString(),
                        expectedMovePct = it.expectedMovePct,
                        expectedMoveHigh = it.expectedMoveHigh,
                        expectedMoveLow = it.expectedMoveLow,
                    )
                },
            )
        }

        private fun StockWeeklyExpectedMoveEntity.toVo() = WeeklyExpectedMove(
            id = id,
            ticker = ticker,
            weekStart = weekStart,
            weekEnd = weekEnd,
            expectedMoveHigh = expectedMoveHigh,
            expectedMoveLow = expectedMoveLow,
            expectedMovePct = expectedMovePct,
        )
    }
}

/** legacy `schemas.py` SigmaOut.weekly_moves 항목 */
data class WeeklyMoveOut(
    val weekStart: String?,
    val weekEnd: String?,
    val expectedMovePct: Double,
    val expectedMoveHigh: Double,
    val expectedMoveLow: Double,
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

/** legacy `schemas.py` StockReport — sigma/actionable_levels/group/quote enrichment 로 var */
data class StockReport(
    val ticker: String,
    val exchange: String,
    val price: Double,
    val change: Double,
    val changeRate: Double,
    val technical: TechnicalOut,
    var sigma: SigmaOut? = null,
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
    val sigmaPosition: String,
    val sigmaSignal: String,
    val sigmaConfidence: Double,
    val expectedMovePct: Double,
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
            sigmaPosition = report.sigma?.sigmaPosition ?: "NEAR_CENTER",
            sigmaSignal = report.sigma?.sigmaSignal ?: "NEUTRAL",
            sigmaConfidence = report.sigma?.sigmaConfidence ?: 0.0,
            expectedMovePct = report.sigma?.expectedMovePct ?: 0.0,
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
