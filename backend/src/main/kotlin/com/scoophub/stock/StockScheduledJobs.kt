package com.scoophub.stock

import com.scoophub.global.schedule.ScheduledJob
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.service.StockCrawlService
import com.scoophub.stock.service.StockFetchMonitor
import com.scoophub.stock.service.StockGexService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `stock/scheduler.py` — 캔들 동기화 / 시그마 계산+분석 파이프라인 (2잡 묶음 네임스페이스) */
class StockScheduledJobs {
    /** 관심종목 일봉 동기화 — job_id stock_sync. 스케줄은 꺼 두고(V35) 수동 실행용으로 남긴다 */
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

    /**
     * 캔들 동기화 → ATM 스트래들 시그마 → 행사가별 GEX → 분석 파이프라인 — job_id stock_daily_sigma (KST 화-토 06:30).
     * 분석이 DB 캔들과 최신 시그마를 읽으므로 순서를 코드로 보장한다 (#176).
     */
    @Component
    class StockDailySigmaJob(
        private val crawlService: StockCrawlService,
        private val fetchMonitor: StockFetchMonitor,
        private val watchlistRepository: StockWatchlistRepository,
        private val analysisService: StockAnalysisService,
        private val gexService: StockGexService,
        private val clock: Clock,
    ) : ScheduledJob {
        override val crawler = "stock"
        override val jobId = "stock_daily_sigma"

        override fun run(params: JsonNode) {
            val tickers = watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }
            if (tickers.isEmpty()) {
                return
            }
            val syncStartedAt = clock.instant()
            fetchMonitor.record(SYNC_JOB_ID, syncStartedAt, crawlService.syncCandles())

            val startedAt = clock.instant()
            val outcome = crawlService.computeSigma(tickers)
            log.info { "Sigma (straddle): ${outcome.saved} saved for ${tickers.size} tickers" }
            fetchMonitor.record(jobId, startedAt, outcome)

            // 시그마 완료 직후 분석+발신
            val resp = analysisService.runAnalysisForTickers(tickers)
            log.info { "Stock analyze (after sigma): ${resp.ok} ok, ${resp.errors} errors" }

            // GEX 는 상세 화면 부가 정보라 분석·발신 뒤에 돌고, 실패해도 잡을 깨지 않는다 (#264)
            try {
                val gexStartedAt = clock.instant()
                val gexOutcome = gexService.computeGex(tickers)
                log.info { "GEX: ${gexOutcome.saved} saved for ${tickers.size} tickers" }
                fetchMonitor.record(GEX_JOB_ID, gexStartedAt, gexOutcome)
            } catch (e: Exception) {
                log.error(e) { "GEX step failed" }
            }
        }

        companion object {
            /** 동기화 결과는 기존 stock_sync 이력에 이어 기록한다 */
            private const val SYNC_JOB_ID = "stock_sync"

            /** GEX 결과는 crawl_logs 에 별도 이력으로 남긴다 */
            private const val GEX_JOB_ID = "stock_gex"
        }
    }
}
