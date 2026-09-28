package com.scoophub.stock

import com.scoophub.external.yahoo.YahooFinanceClient
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.schedule.ScheduledJob
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `stock/scheduler.py` — 캔들 동기화 / 시그마 스캔 / 시그마 계산+분석 파이프라인 (3잡 묶음 네임스페이스) */
class StockScheduledJobs {
    /** 관심종목 일봉 동기화 — job_id stock_sync (interval 60분) */
    @Component
    class StockSyncJob(
        private val provider: YahooFinanceClient,
        private val watchlistRepository: StockWatchlistRepository,
        private val candleRepository: StockCandleRepository,
    ) : ScheduledJob {
        override val crawler = "stock"
        override val jobId = "stock_sync"

        override fun run(params: JsonNode) {
            val tickers = watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }
            if (tickers.isEmpty()) {
                return
            }
            var totalSynced = 0
            for (ticker in tickers) {
                try {
                    val candles = provider.chart(ticker, "1d")
                    if (candles.isNotEmpty()) {
                        candleRepository.saveBatch(
                            tickers = candles.map { it.ticker }.toTypedArray(),
                            intervals = candles.map { it.interval }.toTypedArray(),
                            dates = candles.map { it.date }.toTypedArray(),
                            opens = candles.map { it.open }.toDoubleArray(),
                            highs = candles.map { it.high }.toDoubleArray(),
                            lows = candles.map { it.low }.toDoubleArray(),
                            closes = candles.map { it.close }.toDoubleArray(),
                            volumes = candles.map { it.volume }.toDoubleArray(),
                        )
                        totalSynced += candles.size
                    }
                } catch (e: Exception) {
                    log.error(e) { "Sync failed for $ticker" }
                }
            }
            log.info { "Candle sync: $totalSynced candles for ${tickers.size} tickers" }
        }
    }

    /** usstocksigma 주간 예상움직임 스크래핑 — job_id stock_sigma_scan (월 03:00) */
    @Component
    class StockSigmaScanJob(private val crawlRunner: CrawlRunner, private val sigmaCrawler: SigmaCrawler) :
        ScheduledJob {
        override val crawler = "stock"
        override val jobId = "stock_sigma_scan"

        override fun run(params: JsonNode) {
            val result = crawlRunner.run(sigmaCrawler)
            if (result != null) {
                log.info { "Sigma crawl: ${result.itemsFetched} fetched, ${result.itemsNew} new" }
            }
        }
    }

    /**
     * ATM 스트래들 시그마 계산 + 완료 직후 분석 파이프라인 — job_id stock_daily_sigma (화-토 22:30).
     * sigma→analyze 데이터 의존을 코드로 보장 (#176).
     */
    @Component
    class StockDailySigmaJob(
        private val provider: YahooFinanceClient,
        private val watchlistRepository: StockWatchlistRepository,
        private val sigmaRepository: StockSigmaRepository,
        private val analysisService: StockAnalysisService,
        private val clock: Clock,
    ) : ScheduledJob {
        override val crawler = "stock"
        override val jobId = "stock_daily_sigma"

        override fun run(params: JsonNode) {
            val tickers = watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }
            if (tickers.isEmpty()) {
                return
            }
            val snapshotAt = clock.instant()
            var saved = 0
            for (ticker in tickers) {
                try {
                    val price = provider.quote(ticker)?.regularMarketPrice ?: 0.0
                    if (price <= 0) {
                        continue
                    }
                    for (result in StockSigma.computeSigmaFromOptions(provider, ticker, price, snapshotAt, clock)) {
                        sigmaRepository.upsert(
                            ticker = result.ticker,
                            expiryDate = result.expiryDate ?: continue,
                            snapshotDate = result.snapshotDate,
                            snapshotAt = result.snapshotAt,
                            currentPrice = result.currentPrice,
                            atmStrike = result.atmStrike,
                            atmCall = result.atmCall,
                            atmPut = result.atmPut,
                            expectedMove = result.expectedMove,
                            expectedMovePct = result.expectedMovePct,
                            totalCallVolume = result.totalCallVolume,
                            totalPutVolume = result.totalPutVolume,
                            putCallVolumeRatio = result.putCallVolumeRatio,
                            atmCallVolume = result.atmCallVolume,
                            atmPutVolume = result.atmPutVolume,
                        )
                        saved++
                    }
                } catch (e: Exception) {
                    log.error(e) { "Sigma computation failed for $ticker" }
                }
            }
            log.info { "Sigma (straddle): $saved saved for ${tickers.size} tickers" }

            // 시그마 완료 직후 분석+발신
            val resp = analysisService.runAnalysisForTickers(tickers)
            log.info { "Stock analyze (after sigma): ${resp.ok} ok, ${resp.errors} errors" }
        }
    }
}
