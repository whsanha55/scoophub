package com.scoophub.stock.dto

import com.scoophub.stock.ChartPoint
import com.scoophub.stock.SigmaBand
import com.scoophub.stock.SigmaStateEnum
import com.scoophub.stock.SigmaWeek
import com.scoophub.stock.StockSigma
import com.scoophub.stock.StockSigmaBand
import com.scoophub.stock.TrendStateEnum
import com.scoophub.stock.entity.StockCandleEntity
import com.scoophub.stock.entity.StockSigmaEntity
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong

private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

/** legacy `schemas.py` TechnicalOut — 매수·매도 점수 대신 50/200 추세 상태 (#251) */
data class TechnicalOut(val trend: String, val trendSince: LocalDate?, val technicalDetails: JsonNode)

/** /stock/detail 최근 종가 — DB 마지막 일봉 (#258, 실시간 시세 호출 제거) */
data class LastCandleOut(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
) {
    companion object {
        fun from(row: StockCandleEntity) = LastCandleOut(
            date = row.date,
            open = row.open,
            high = row.high,
            low = row.low,
            close = row.close,
            volume = row.volume,
        )
    }
}

/** legacy `schemas.py` StockReport — group/lastCandle enrichment 로 var */
data class StockReport(
    val ticker: String,
    val exchange: String,
    val price: Double,
    val change: Double,
    val changeRate: Double,
    val technical: TechnicalOut,
    var group: String? = null,
    val dataDate: Instant? = null,
    val isStale: Boolean? = null,
    var lastCandle: LastCandleOut? = null,
    var sigma: SigmaBandOut? = null,
)

/** legacy `schemas.py` StockSummary — /stock/report/all summarize=true 요약 */
data class StockSummary(
    val ticker: String,
    val exchange: String,
    val price: Double,
    val change: Double,
    val changeRate: Double,
    val trend: String,
    val trendSince: LocalDate?,
    val group: String? = null,
    val dataDate: Instant? = null,
    val isStale: Boolean? = null,
    val sigma: SigmaBandOut? = null,
) {
    companion object {
        fun from(report: StockReport) = StockSummary(
            ticker = report.ticker,
            exchange = report.exchange,
            price = report.price,
            change = report.change,
            changeRate = report.changeRate,
            trend = report.technical.trend,
            trendSince = report.technical.trendSince,
            group = report.group,
            dataDate = report.dataDate,
            isStale = report.isStale,
            sigma = report.sigma,
        )
    }
}

/** 주간 σ 밴드 위치 (#260). asOf 종가가 anchorDate 앵커 밴드에서 몇 σ 인지 */
data class SigmaBandOut(
    val position: Double,
    val state: SigmaStateEnum,
    val upper: Double,
    val lower: Double,
    val anchorPrice: Double,
    val anchorDate: LocalDate,
    val expiryDate: LocalDate,
    val expectedMove: Double,
    val expectedMovePct: Double,
    val asOf: LocalDate,
) {
    companion object {
        fun of(band: SigmaBand, close: Double, asOf: LocalDate): SigmaBandOut {
            val position = band.position(close)
            return SigmaBandOut(
                position = round2(position),
                state = StockSigmaBand.state(position),
                upper = round2(band.upper),
                lower = round2(band.lower),
                anchorPrice = band.anchorPrice,
                anchorDate = band.snapshotDate,
                expiryDate = band.expiryDate,
                expectedMove = band.expectedMove,
                expectedMovePct = round2(band.expectedMove / band.anchorPrice * 100),
                asOf = asOf,
            )
        }
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

/** 상세 차트 (#261) — 최근 1년 일봉. 밴드가 없는 날은 sigma 필드가 null */
data class StockChartOut(val ticker: String, val points: List<StockChartPointOut>)

data class StockChartPointOut(
    val date: LocalDate,
    val close: Double,
    val sma50: Double?,
    val sma200: Double?,
    val sigmaUpper: Double?,
    val sigmaLower: Double?,
    val sigmaAnchor: Double?,
    val sigmaPosition: Double?,
    val sigmaState: SigmaStateEnum?,
    val cross: TrendStateEnum?,
) {
    companion object {
        fun from(point: ChartPoint) = StockChartPointOut(
            date = point.date,
            close = point.close,
            sma50 = point.sma50?.let { round2(it) },
            sma200 = point.sma200?.let { round2(it) },
            sigmaUpper = point.band?.let { round2(it.upper) },
            sigmaLower = point.band?.let { round2(it.lower) },
            sigmaAnchor = point.band?.anchorPrice,
            sigmaPosition = point.position?.let { round2(it) },
            sigmaState = point.state,
            cross = point.cross,
        )
    }
}

/** 주간 마감 후 가격 변화 (#262). return_nw 는 마감 종가 대비 N주(5N 거래일) 뒤 종가 수익률(%) */
data class SigmaWeekOut(
    val anchorDate: LocalDate,
    val anchorPrice: Double,
    val expectedMove: Double,
    val expiryDate: LocalDate,
    val closeDate: LocalDate,
    val close: Double,
    val sigmaPosition: Double,
    val sigmaState: SigmaStateEnum,
    val return1w: Double?,
    val return2w: Double?,
    val return4w: Double?,
) {
    companion object {
        fun from(week: SigmaWeek) = SigmaWeekOut(
            anchorDate = week.band.snapshotDate,
            anchorPrice = week.band.anchorPrice,
            expectedMove = week.band.expectedMove,
            expiryDate = week.band.expiryDate,
            closeDate = week.closeDate,
            close = week.close,
            sigmaPosition = round2(week.position),
            sigmaState = week.state,
            return1w = week.return1w?.let { round2(it) },
            return2w = week.return2w?.let { round2(it) },
            return4w = week.return4w?.let { round2(it) },
        )
    }
}
