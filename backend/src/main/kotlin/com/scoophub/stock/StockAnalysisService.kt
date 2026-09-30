package com.scoophub.stock

import com.scoophub.external.yahoo.YahooFinanceClient
import com.scoophub.global.jackson.scalar
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.repository.StockWeeklyExpectedMoveRepository
import com.scoophub.stock.vo.SigmaModel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock

private val log = KotlinLogging.logger {}

data class AnalyzeResult(val ticker: String, val status: String, val detail: String? = null)

data class AnalyzeResponse(val total: Int, val ok: Int, val errors: Int, val results: List<AnalyzeResult>)

/** legacy `stock/analysis_service.py` — 티커 분석 + 저장 + 리포트 발신 연쇄 */
@Component
class StockAnalysisService(
    private val provider: YahooFinanceClient,
    private val analysisRepository: StockAnalysisResultRepository,
    private val watchlistRepository: StockWatchlistRepository,
    private val sigmaRepository: StockSigmaRepository,
    private val wemRepository: StockWeeklyExpectedMoveRepository,
    private val reportBuilder: StockReportBuilder,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    fun runAnalysisForTickers(tickers: List<String>): AnalyzeResponse {
        log.info { "run_analysis_for_tickers() 진입 — tickers=$tickers" }
        val results = mutableListOf<AnalyzeResult>()
        var ok = 0
        var errors = 0

        for (ticker in tickers) {
            try {
                val upper = ticker.uppercase()
                val quote = provider.quote(upper)
                val price = quote?.regularMarketPrice ?: 0.0
                val change = quote?.regularMarketChange ?: 0.0
                val changeRate = quote?.regularMarketChangePercent ?: 0.0

                if (price == 0.0) {
                    results += AnalyzeResult(upper, "error", "Price unavailable — provider returned no data")
                    errors++
                    continue
                }

                val candles = provider.chart(upper, "1d")
                if (candles.isEmpty()) {
                    // 빈 캔들(provider 실패) 시 가짜 분석이 ok 로 영속화되는 것 방지
                    results += AnalyzeResult(upper, "error", "No candle data — provider returned empty")
                    errors++
                    continue
                }

                val exchange = watchlistRepository.findByTickerAndIsActive(upper)?.exchange ?: "NAS"
                val report = StockSignal.generateReport(
                    upper,
                    price,
                    candles,
                    clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                )

                // details dict + sigma enrichment
                val details: ObjectNode = jsonMapper.valueToTree(report.technicalDetails)
                fetchSigmaEnrichment(upper, price)?.let { details.set("sigma_data", it) }
                analysisRepository.upsert(
                    ticker = upper,
                    exchange = exchange,
                    timeframe = "1D",
                    signal = report.signal.name,
                    totalScore = report.totalScore,
                    confidence = report.confidence,
                    marketRegime = report.marketRegime.name,
                    price = price,
                    change = change,
                    changeRate = changeRate,
                    technicalScores = jsonMapper.writeValueAsString(report.technicalScores),
                    technicalDetails = details.toString(),
                )

                // 다중 기간(1W/1M): resample → 분석 → 평면 저장. 캔들 부족 시 스킵.
                saveMultiTimeframe(upper, exchange, candles, price, change, changeRate)

                results += AnalyzeResult(upper, "ok")
                ok++
            } catch (e: Exception) {
                log.warn { "Analysis failed for $ticker: ${e.message}" }
                results += AnalyzeResult(ticker, "error", e.message)
                errors++
            }
        }

        // 발신 연쇄 — 성공 1건 이상 시. 발신 실패해도 분석은 이미 저장됨.
        if (ok > 0) {
            try {
                reportBuilder.run(tickers)
            } catch (e: Exception) {
                log.warn { "daily report dispatch failed (non-fatal): ${e.message}" }
            }
        }
        return AnalyzeResponse(tickers.size, ok, errors, results)
    }

    /** 분석 시점 sigma + WEM 스냅샷 (issue #49 JSON 스키마) */
    fun fetchSigmaEnrichment(ticker: String, price: Double): JsonNode? {
        val sigmaData: ObjectNode = jsonMapper.createObjectNode()

        // 1. stock_sigma (ATM straddle, nearest expiry)
        sigmaRepository.findFirstByTickerOrderBySnapshotDateDescSnapshotAtDesc(ticker)?.let { s ->
            sigmaData.set(
                "straddle",
                jsonMapper.createObjectNode().apply {
                    put("expiry_date", s.expiryDate?.toString())
                    put("atm_strike", s.atmStrike)
                    put("atm_call", s.atmCall)
                    put("atm_put", s.atmPut)
                    put("expected_move", s.expectedMove)
                    put("expected_move_pct", s.expectedMovePct)
                    put("put_call_volume_ratio", s.putCallVolumeRatio)
                    put("total_call_volume", s.totalCallVolume)
                    put("total_put_volume", s.totalPutVolume)
                    put("snapshot_date", s.snapshotDate?.toString())
                },
            )
        }

        // 2. stock_weekly_expected_moves
        wemRepository.findByTickerOrderByWeekStartDesc(ticker, org.springframework.data.domain.Limit.of(1))
            .firstOrNull()
            ?.let { w ->
                val sigmaRange = SigmaModel.computeSigmaRange(
                    com.scoophub.stock.vo.WeeklyExpectedMove(
                        id = w.id,
                        ticker = w.ticker,
                        weekStart = w.weekStart,
                        weekEnd = w.weekEnd,
                        expectedMoveHigh = w.expectedMoveHigh,
                        expectedMoveLow = w.expectedMoveLow,
                        expectedMovePct = w.expectedMovePct,
                    ),
                    price,
                )
                val sigmaSignal = SigmaModel.generateSigmaSignal(sigmaRange)
                sigmaData.set(
                    "weekly_expected_move",
                    jsonMapper.createObjectNode().apply {
                        put("week_start", w.weekStart?.toString())
                        put("week_end", w.weekEnd?.toString())
                        put("expected_move_high", w.expectedMoveHigh)
                        put("expected_move_low", w.expectedMoveLow)
                        put("expected_move_pct", w.expectedMovePct)
                        put("sigma_position", sigmaSignal.sigmaPosition.name)
                        put("sigma_signal", sigmaSignal.signal.name)
                        put("sigma_confidence", sigmaSignal.confidence)
                        put("center", sigmaRange.center)
                        put("upper_1sigma", sigmaRange.upper1sigma)
                        put("lower_1sigma", sigmaRange.lower1sigma)
                    },
                )
            }

        return if (sigmaData.isEmpty) null else sigmaData
    }

    private fun saveMultiTimeframe(
        ticker: String,
        exchange: String,
        dailyCandles: List<com.scoophub.stock.vo.Candle>,
        price: Double,
        change: Double,
        changeRate: Double,
    ) {
        for (rule in listOf("1W" to StockResample::weekly, "1M" to StockResample::monthly)) {
            try {
                val resampled = rule.second(dailyCandles)
                if (resampled.isEmpty()) {
                    continue
                }
                val report = StockSignal.generateReport(
                    ticker,
                    price,
                    resampled,
                    clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                )
                analysisRepository.upsert(
                    ticker = ticker,
                    exchange = exchange,
                    timeframe = rule.first,
                    signal = report.signal.name,
                    totalScore = report.totalScore,
                    confidence = report.confidence,
                    marketRegime = report.marketRegime.name,
                    price = price,
                    change = change,
                    changeRate = changeRate,
                    technicalScores = jsonMapper.writeValueAsString(report.technicalScores),
                    technicalDetails = jsonMapper.writeValueAsString(report.technicalDetails),
                )
            } catch (e: Exception) {
                log.warn { "multi-timeframe ${rule.first} analysis failed for $ticker: ${e.message}" }
            }
        }
    }
}
