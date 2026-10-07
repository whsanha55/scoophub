package com.scoophub.stock

import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.schedule.ScheduledJob
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.service.StockCrawlService
import com.scoophub.stock.service.StockFetchMonitor
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
        private val crawlService: StockCrawlService,
        private val fetchMonitor: StockFetchMonitor,
        private val clock: Clock,
    ) : ScheduledJob {
        override val crawler = "stock"
        override val jobId = "stock_sync"

        override fun run(params: JsonNode) {
            val startedAt = clock.instant()
            fetchMonitor.record(jobId, startedAt, crawlService.syncCandles())
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
        private val crawlService: StockCrawlService,
        private val fetchMonitor: StockFetchMonitor,
        private val watchlistRepository: StockWatchlistRepository,
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
            val startedAt = clock.instant()
            val outcome = crawlService.computeSigma(tickers)
            log.info { "Sigma (straddle): ${outcome.saved} saved for ${tickers.size} tickers" }
            fetchMonitor.record(jobId, startedAt, outcome)

            // 시그마 완료 직후 분석+발신
            val resp = analysisService.runAnalysisForTickers(tickers)
            log.info { "Stock analyze (after sigma): ${resp.ok} ok, ${resp.errors} errors" }
        }
    }
}
