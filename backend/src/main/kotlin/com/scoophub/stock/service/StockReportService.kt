package com.scoophub.stock.service

import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.stock.StockReportBuilder
import com.scoophub.stock.dto.ActionableLevelsOut
import com.scoophub.stock.dto.SigmaSnapshotOut
import com.scoophub.stock.dto.StockQuoteOut
import com.scoophub.stock.dto.StockReport
import com.scoophub.stock.dto.StockSummary
import com.scoophub.stock.dto.TechnicalOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/** legacy `stock/router.py` 리포트 조회 — 분석 결과 + actionable levels + group */
@Service
class StockReportService(
    private val provider: AlpacaMarketDataClient,
    private val watchlistRepository: StockWatchlistRepository,
    private val analysisRepository: StockAnalysisResultRepository,
    private val sigmaRepository: StockSigmaRepository,
    private val clock: Clock,
) {
    fun findReports(tickers: List<String>, timeframe: String): List<StockReport> =
        analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(tickers, timeframe).map { buildReport(it) }

    /** 1D 분석 + 실시간 quote. 분석이 없으면 null */
    fun findDetail(ticker: String): StockReport? {
        val row = analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(listOf(ticker), "1D")
            .firstOrNull()
            ?: return null
        val report = buildReport(row)
        attachQuote(report, ticker)
        return report
    }

    fun findAll(timeframe: String): List<StockReport> =
        analysisRepository.findByTimeframeOrderByAnalyzedAtDesc(timeframe).map { buildReport(it) }

    fun findAllSummaries(timeframe: String): List<StockSummary> =
        analysisRepository.findByTimeframeOrderByAnalyzedAtDesc(timeframe)
            .map { StockSummary.from(buildReport(it), it) }

    fun findLatestSigma(ticker: String): SigmaSnapshotOut? =
        sigmaRepository.findFirstByTickerOrderBySnapshotDateDescExpiryDateAsc(ticker)?.let { SigmaSnapshotOut.from(it) }

    /** `_analysis_row_to_report` + `_enrich_report_levels` */
    private fun buildReport(row: StockAnalysisResultEntity): StockReport {
        val technical = TechnicalOut(
            signal = row.signal,
            totalScore = row.totalScore,
            confidence = row.confidence,
            marketRegime = row.marketRegime,
            technicalScores = row.technicalScores,
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
        enrichReportLevels(report, row)
        return report
    }

    /** StockReport 에 actionable_levels + group 채우기 (#149) */
    private fun enrichReportLevels(report: StockReport, row: StockAnalysisResultEntity) {
        // 1. sigma range 확보 (straddle 스냅샷 — Telegram 리포트와 동일 로직)
        val sigmaRange = StockReportBuilder.sigmaRangeFromSnapshot(row.technicalDetails.get("sigma_data"), report.price)

        StockReportBuilder.computeActionableLevels(report.price, sigmaRange, row.technicalDetails)?.let { levels ->
            report.actionableLevels = ActionableLevelsOut.from(levels)
        }

        // 2. group 매핑
        watchlistRepository.findByTickerAndIsActive(report.ticker)?.group?.takeIf { it.isNotEmpty() }?.let {
            report.group = it
        }
    }

    /** 실시간 quote. 실패 시 quote=None, 나머지 정상 (legacy /stock/detail) */
    private fun attachQuote(report: StockReport, ticker: String) {
        try {
            val q = provider.snapshots(listOf(ticker))[ticker] ?: return
            if (q.price != 0.0) {
                report.quote = StockQuoteOut(
                    price = q.price,
                    change = q.change,
                    changeRate = q.changePercent,
                    volume = q.volume.takeIf { it != 0.0 },
                    high = q.high.takeIf { it != 0.0 },
                    low = q.low.takeIf { it != 0.0 },
                    open = q.open.takeIf { it != 0.0 },
                    timestamp = clock.instant(),
                )
            }
        } catch (e: Exception) {
            log.warn { "quote fetch failed for $ticker: ${e.message}" }
        }
    }

    companion object {
        private const val STALE_SECONDS = 86_400L // 24h — is_stale 기준
    }
}
