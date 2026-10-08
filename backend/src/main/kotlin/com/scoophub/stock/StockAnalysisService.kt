package com.scoophub.stock

import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.external.alpaca.AlpacaMarketDataException
import com.scoophub.global.jackson.scalar
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.vo.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

private val log = KotlinLogging.logger {}

data class AnalyzeResult(val ticker: String, val status: String, val detail: String? = null)

data class AnalyzeResponse(val total: Int, val ok: Int, val errors: Int, val results: List<AnalyzeResult>)

/** legacy `stock/analysis_service.py` — 티커 분석 + 저장 + 리포트 발신 연쇄 */
@Component
class StockAnalysisService(
    private val provider: AlpacaMarketDataClient,
    private val analysisRepository: StockAnalysisResultRepository,
    private val candleRepository: StockCandleRepository,
    private val watchlistRepository: StockWatchlistRepository,
    private val sigmaRepository: StockSigmaRepository,
    private val reportBuilder: StockReportBuilder,
    private val jsonMapper: JsonMapper,
) {

    fun runAnalysisForTickers(tickers: List<String>): AnalyzeResponse {
        log.info { "run_analysis_for_tickers() 진입 — tickers=$tickers" }
        val results = mutableListOf<AnalyzeResult>()
        var ok = 0
        var errors = 0
        val symbols = tickers.map { it.uppercase() }
        val quotes = try {
            provider.snapshots(symbols)
        } catch (e: AlpacaMarketDataException) {
            log.warn { "market data fetch failed for analysis: ${e.message}" }
            return AnalyzeResponse(tickers.size, 0, tickers.size, symbols.map { AnalyzeResult(it, "error", e.message) })
        }

        for (ticker in tickers) {
            try {
                val upper = ticker.uppercase()
                val quote = quotes[upper]
                val price = quote?.price ?: 0.0
                val change = quote?.change ?: 0.0
                val changeRate = quote?.changePercent ?: 0.0

                if (price == 0.0) {
                    results += AnalyzeResult(upper, "error", "Price unavailable — provider returned no data")
                    errors++
                    continue
                }

                val candles = findDailyCandles(upper)
                if (candles.isEmpty()) {
                    // 빈 캔들 시 가짜 분석이 ok 로 영속화되는 것 방지
                    results += AnalyzeResult(upper, "error", "No candle data — run candle sync first")
                    errors++
                    continue
                }

                val exchange = watchlistRepository.findByTickerAndIsActive(upper)?.exchange ?: "NAS"
                val trend = StockTrend.evaluate(candles)

                // details dict + sigma enrichment
                val details: ObjectNode = jsonMapper.valueToTree(StockTechnical.analyze(candles))
                fetchSigmaEnrichment(upper)?.let { details.set("sigma_data", it) }
                analysisRepository.upsert(
                    ticker = upper,
                    exchange = exchange,
                    trend = trend.state.name,
                    trendSince = trend.since,
                    candleDate = candles.last().date,
                    price = price,
                    change = change,
                    changeRate = changeRate,
                    technicalDetails = details.toString(),
                )

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

    /** 분석 시점 sigma(straddle) 스냅샷 (issue #49 JSON 스키마). 과거 분석 행의 WEM(주간 예상변동폭) JSON 은 데이터 불변 원칙으로 남겨두고 읽지 않음 */
    fun fetchSigmaEnrichment(ticker: String): JsonNode? {
        val sigmaData: ObjectNode = jsonMapper.createObjectNode()

        // stock_sigma (ATM straddle, 최신 스냅샷의 가장 가까운 만기)
        sigmaRepository.findFirstByTickerOrderBySnapshotDateDescExpiryDateAsc(ticker)?.let { s ->
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

        return if (sigmaData.isEmpty) null else sigmaData
    }

    private fun findDailyCandles(ticker: String): List<Candle> =
        candleRepository.findByTickerAndIntervalOrderByDate(ticker, "1D").map {
            Candle(it.ticker, it.interval, it.date, it.open, it.high, it.low, it.close, it.volume)
        }
}
