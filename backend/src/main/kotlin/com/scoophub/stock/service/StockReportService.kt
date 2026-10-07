package com.scoophub.stock.service

import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.global.jackson.scalar
import com.scoophub.stock.StockReportBuilder
import com.scoophub.stock.dto.ActionableLevelsOut
import com.scoophub.stock.dto.SigmaOut
import com.scoophub.stock.dto.SigmaSnapshotOut
import com.scoophub.stock.dto.StockQuoteOut
import com.scoophub.stock.dto.StockReport
import com.scoophub.stock.dto.StockSummary
import com.scoophub.stock.dto.TechnicalOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockWeeklyExpectedMoveEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.repository.StockWeeklyExpectedMoveRepository
import com.scoophub.stock.vo.SigmaModel
import com.scoophub.stock.vo.SigmaRange
import com.scoophub.stock.vo.WeeklyExpectedMove
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/** legacy `stock/router.py` 리포트 조회 — 분석 결과 + sigma 폴백 + actionable levels + group */
@Service
class StockReportService(
    private val provider: AlpacaMarketDataClient,
    private val watchlistRepository: StockWatchlistRepository,
    private val analysisRepository: StockAnalysisResultRepository,
    private val wemRepository: StockWeeklyExpectedMoveRepository,
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
            // 요약 시 sigma 폴백은 최근 1건만 (legacy limit=1)
            .map { StockSummary.from(buildReport(it, sigmaFallbackLimit = 1), it) }

    fun findLatestSigma(ticker: String): SigmaSnapshotOut? =
        sigmaRepository.findFirstByTickerOrderBySnapshotDateDescExpiryDateAsc(ticker)?.let { SigmaSnapshotOut.from(it) }

    /** `_analysis_row_to_report` + `_enrich_sigma_fallback` + `_enrich_report_levels` */
    private fun buildReport(row: StockAnalysisResultEntity, sigmaFallbackLimit: Int = 4): StockReport {
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
            sigma = SigmaOut.fromPersistedSnapshot(row.technicalDetails),
            dataDate = row.analyzedAt,
            isStale = Duration.between(row.analyzedAt, clock.instant()).seconds > STALE_SECONDS,
        )
        if (report.sigma == null) {
            enrichSigmaFallback(report, row.ticker, sigmaFallbackLimit)
        }
        enrichReportLevels(report, row)
        return report
    }

    /** 저장 스냅샷이 없을 때 WEM 실시간 데이터로 report.sigma 채움 */
    private fun enrichSigmaFallback(report: StockReport, ticker: String, limit: Int) {
        val wemList = wemRepository.findByTickerOrderByWeekStartDesc(ticker, Limit.of(limit))
        if (wemList.isEmpty()) {
            return
        }
        report.sigma = SigmaOut.fromWem(wemList, report.price)
    }

    /** StockReport 에 actionable_levels + group 채우기 (#149) */
    private fun enrichReportLevels(report: StockReport, row: StockAnalysisResultEntity) {
        // 1. sigma range 확보 (저장된 snapshot 우선, 없으면 WEMRepo 실시간 산출)
        val wemSnapshot = row.technicalDetails.get("sigma_data")?.get("weekly_expected_move")
        val upper1 = wemSnapshot?.scalar("upper_1sigma")?.toDoubleOrNull()
        val lower1 = wemSnapshot?.scalar("lower_1sigma")?.toDoubleOrNull()
        val sigmaRange = if (upper1 != null && lower1 != null) {
            // compute_actionable_levels 는 upper/lower_1sigma 두 속성만 읽음
            SigmaRange(ticker = report.ticker, upper1sigma = upper1, lower1sigma = lower1)
        } else {
            val wem = wemRepository.findByTickerOrderByWeekStartDesc(report.ticker, Limit.of(1)).firstOrNull()
            if (wem != null) {
                try {
                    SigmaModel.computeSigmaRange(wem.toVo(), report.price)
                } catch (e: Exception) {
                    log.warn { "sigma range compute failed for ${report.ticker}: ${e.message}" }
                    null
                }
            } else {
                null
            }
        }

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

    private fun StockWeeklyExpectedMoveEntity.toVo() = WeeklyExpectedMove(
        id = id,
        ticker = ticker,
        weekStart = weekStart,
        weekEnd = weekEnd,
        expectedMoveHigh = expectedMoveHigh,
        expectedMoveLow = expectedMoveLow,
        expectedMovePct = expectedMovePct,
    )

    companion object {
        private const val STALE_SECONDS = 86_400L // 24h — is_stale 기준
    }
}
