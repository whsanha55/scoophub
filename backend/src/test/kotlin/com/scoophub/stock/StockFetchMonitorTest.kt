package com.scoophub.stock

import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.external.alpaca.AlpacaMarketDataException
import com.scoophub.global.crawl.entity.CrawlLogEntity
import com.scoophub.global.crawl.enums.CrawlStatusEnum
import com.scoophub.global.crawl.repository.CrawlLogRepository
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.stock.entity.StockWatchlistEntity
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.service.StockCrawlService
import com.scoophub.stock.service.StockFetchMonitor
import com.scoophub.stock.vo.FetchOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StockFetchMonitorTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC)
    private val crawlLogRepository = mockk<CrawlLogRepository>()
    private val notifyRouter = mockk<NotifyRouter>(relaxed = true)
    private val monitor = StockFetchMonitor(crawlLogRepository, notifyRouter, clock)
    private val savedLog = slot<CrawlLogEntity>()

    private fun givenPreviousLog(status: CrawlStatusEnum?) {
        every {
            crawlLogRepository.findByCrawlerAndCrawlerDetailOrderByStartedAtDesc("stock", "stock_sync", any())
        } returns
            listOfNotNull(status?.let { log(it) })
        every { crawlLogRepository.save(capture(savedLog)) } answers { savedLog.captured }
    }

    private fun log(status: CrawlStatusEnum) = CrawlLogEntity(
        crawler = "stock",
        crawlerDetail = "stock_sync",
        status = status,
        itemsFetched = 0,
        itemsNew = 0,
        errorMessage = null,
        startedAt = clock.instant(),
        finishedAt = clock.instant(),
    )

    @Test
    fun `시세 API 가 429 로 막히면 error 로그를 남기고 상태 코드를 담아 1회 알린다`() {
        // given
        givenPreviousLog(null)
        val provider = mockk<AlpacaMarketDataClient>()
        every { provider.dailyBars(any()) } throws AlpacaMarketDataException(429, "HTTP 429")
        val watchlistRepository = mockk<StockWatchlistRepository>()
        every { watchlistRepository.findByIsActiveOrderByAddedAt() } returns listOf(
            mockk<StockWatchlistEntity> { every { ticker } returns "QQQ" },
            mockk<StockWatchlistEntity> { every { ticker } returns "AAPL" },
        )
        val crawlService = StockCrawlService(provider, watchlistRepository, mockk(), mockk(), clock)
        val message = slot<NotifyMessage>()

        // when
        monitor.record("stock_sync", clock.instant(), crawlService.syncCandles())

        // then
        assertThat(savedLog.captured.status).isEqualTo(CrawlStatusEnum.ERROR)
        assertThat(savedLog.captured.errorMessage).contains("QQQ: HTTP 429")
        verify(exactly = 1) {
            notifyRouter.dispatch("stock", "fetch-alert", "stock:fetch-alert:stock_sync:2026-10-07", capture(message))
        }
        assertThat(message.captured.text).contains("2/2", "HTTP 429 ×2")
    }

    @Test
    fun `일부 종목 실패가 처음이면 알리지 않는다`() {
        // given
        givenPreviousLog(CrawlStatusEnum.SUCCESS)

        // when
        monitor.record(
            "stock_sync",
            clock.instant(),
            FetchOutcome(total = 2, saved = 10, failures = mapOf("QQQ" to "no candles")),
        )

        // then
        assertThat(savedLog.captured.status).isEqualTo(CrawlStatusEnum.PARTIAL)
        verify(exactly = 0) { notifyRouter.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `일부 종목 실패가 2회 연속이면 알린다`() {
        // given
        givenPreviousLog(CrawlStatusEnum.PARTIAL)

        // when
        monitor.record(
            "stock_sync",
            clock.instant(),
            FetchOutcome(total = 2, saved = 10, failures = mapOf("QQQ" to "no candles")),
        )

        // then
        verify(exactly = 1) { notifyRouter.dispatch("stock", "fetch-alert", any(), any()) }
    }

    @Test
    fun `모두 성공하면 success 로그만 남긴다`() {
        // given
        givenPreviousLog(CrawlStatusEnum.ERROR)

        // when
        monitor.record("stock_sync", clock.instant(), FetchOutcome(total = 2, saved = 20, failures = emptyMap()))

        // then
        assertThat(savedLog.captured.status).isEqualTo(CrawlStatusEnum.SUCCESS)
        assertThat(savedLog.captured.errorMessage).isNull()
        verify(exactly = 0) { notifyRouter.dispatch(any(), any(), any(), any()) }
    }
}
