package com.scoophub.stock

import com.scoophub.stock.entity.StockWatchlistEntity
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.service.StockCrawlService
import com.scoophub.stock.service.StockFetchMonitor
import com.scoophub.stock.vo.FetchOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StockDailySigmaJobTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-07T21:30:00Z"), ZoneOffset.UTC)
    private val crawlService = mockk<StockCrawlService>()
    private val fetchMonitor = mockk<StockFetchMonitor>(relaxed = true)
    private val watchlistRepository = mockk<StockWatchlistRepository>()
    private val analysisService = mockk<StockAnalysisService>()
    private val job = StockScheduledJobs.StockDailySigmaJob(
        crawlService,
        fetchMonitor,
        watchlistRepository,
        analysisService,
        clock,
    )

    @Test
    fun `캔들 동기화 후 시그마를 계산하고 마지막에 분석한다`() {
        // given
        every { watchlistRepository.findByIsActiveOrderByAddedAt() } returns listOf(
            mockk<StockWatchlistEntity> { every { ticker } returns "QQQ" },
        )
        val outcome = FetchOutcome(total = 1, saved = 1, failures = emptyMap())
        every { crawlService.syncCandles() } returns outcome
        every { crawlService.computeSigma(listOf("QQQ")) } returns outcome
        every { analysisService.runAnalysisForTickers(listOf("QQQ")) } returns AnalyzeResponse(1, 1, 0, emptyList())

        // when
        job.run(JsonNodeFactory.instance.objectNode())

        // then
        verifyOrder {
            crawlService.syncCandles()
            fetchMonitor.record("stock_sync", any(), outcome)
            crawlService.computeSigma(listOf("QQQ"))
            fetchMonitor.record("stock_daily_sigma", any(), outcome)
            analysisService.runAnalysisForTickers(listOf("QQQ"))
        }
    }
}
