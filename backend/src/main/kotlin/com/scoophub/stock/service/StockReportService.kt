package com.scoophub.stock.service

import com.scoophub.stock.dto.LastCandleOut
import com.scoophub.stock.dto.SigmaSnapshotOut
import com.scoophub.stock.dto.StockReport
import com.scoophub.stock.dto.StockSummary
import com.scoophub.stock.dto.TechnicalOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/** legacy `stock/router.py` 리포트 조회 — 분석 결과 + group */
@Service
class StockReportService(
    private val watchlistRepository: StockWatchlistRepository,
    private val analysisRepository: StockAnalysisResultRepository,
    private val sigmaRepository: StockSigmaRepository,
    private val candleRepository: StockCandleRepository,
    private val clock: Clock,
) {
    fun findReports(tickers: List<String>): List<StockReport> =
        analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(tickers, TIMEFRAME).map { buildReport(it) }

    /** 1D 분석 + DB 마지막 일봉. 분석이 없으면 null. 시세는 하루 1회 배치 수집분만 쓴다 (#258) */
    fun findDetail(ticker: String): StockReport? {
        val row = analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(listOf(ticker), TIMEFRAME)
            .firstOrNull()
            ?: return null
        val report = buildReport(row)
        report.lastCandle = candleRepository.findFirstByTickerAndIntervalOrderByDateDesc(ticker, TIMEFRAME)
            ?.let { LastCandleOut.from(it) }
        return report
    }

    fun findAll(): List<StockReport> =
        analysisRepository.findByTimeframeOrderByAnalyzedAtDesc(TIMEFRAME).map { buildReport(it) }

    fun findAllSummaries(): List<StockSummary> = analysisRepository.findByTimeframeOrderByAnalyzedAtDesc(TIMEFRAME)
        .map { StockSummary.from(buildReport(it)) }

    fun findLatestSigma(ticker: String): SigmaSnapshotOut? =
        sigmaRepository.findFirstByTickerOrderBySnapshotDateDescExpiryDateAsc(ticker)?.let { SigmaSnapshotOut.from(it) }

    /** `_analysis_row_to_report` + group 매핑 */
    private fun buildReport(row: StockAnalysisResultEntity): StockReport {
        val technical = TechnicalOut(
            trend = row.trend,
            trendSince = row.trendSince,
            technicalDetails = row.technicalDetails,
        )
        val report = StockReport(
            ticker = row.ticker,
            exchange = row.exchange,
            price = row.price,
            change = row.change,
            changeRate = row.changeRate,
            technical = technical,
            dataDate = row.analyzedAt,
            isStale = Duration.between(row.analyzedAt, clock.instant()).seconds > STALE_SECONDS,
        )
        watchlistRepository.findByTickerAndIsActive(report.ticker)?.group?.takeIf { it.isNotEmpty() }?.let {
            report.group = it
        }
        return report
    }

    companion object {
        private const val STALE_SECONDS = 86_400L // 24h — is_stale 기준

        /** 다기간(1W/1M) 분석은 신호용이라 #251 에서 제거해 일봉만 남는다 */
        private const val TIMEFRAME = "1D"
    }
}
