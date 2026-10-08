package com.scoophub.stock

import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockWatchlistEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 추세 전환 리포트 — repository/router 만 목킹한 순수 단위 테스트(Spring 컨텍스트 없음) */
class StockReportBuilderTest {

    private val analysisRepository = mockk<StockAnalysisResultRepository>()
    private val watchlistRepository = mockk<StockWatchlistRepository>()
    private val notifyRouter = mockk<NotifyRouter>(relaxUnitFun = true)
    private val clock = Clock.fixed(Instant.parse("2024-01-05T10:15:00Z"), ZoneOffset.UTC)
    private val builder = StockReportBuilder(analysisRepository, watchlistRepository, notifyRouter, clock)
    private val jsonMapper = JsonMapper.builder().build()
    private val lastCandle = LocalDate.parse("2024-01-04")

    private fun row(
        ticker: String,
        trend: TrendStateEnum = TrendStateEnum.GOLDEN,
        trendSince: LocalDate? = lastCandle,
    ) = StockAnalysisResultEntity(
        ticker = ticker,
        exchange = "NAS",
        timeframe = "1D",
        trend = trend.name,
        trendSince = trendSince,
        candleDate = lastCandle,
        price = 100.0,
        change = 1.0,
        changeRate = 1.2,
        technicalDetails = jsonMapper.readTree("""{"sma50": 98.5, "sma200": 97.25}"""),
        analyzedAt = Instant.parse("2024-01-05T10:00:00Z"),
    )

    private fun stub(rows: List<StockAnalysisResultEntity>) {
        every { watchlistRepository.findByIsActiveOrderByAddedAt() } returns rows.map {
            StockWatchlistEntity(ticker = it.ticker, addedAt = Instant.parse("2024-01-01T00:00:00Z"))
        }
        every { analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(any(), "1D") } answers {
            val tickers = firstArg<List<String>>()
            rows.filter { it.ticker in tickers }
        }
    }

    @Test
    fun `전환 종목이 있으면 단일 메시지를 발신하고 payload_key 에 part 접미사가 없다`() {
        // given
        stub(listOf(row("AAPL")))

        // when
        val result = builder.run()

        // then
        assertThat(result).contains("주식 추세 전환", "기준일 2024-01-04", "AAPL", "Scoophub에서 전체 보기")
        val keys = mutableListOf<String>()
        verify(exactly = 1) { notifyRouter.dispatch("stock", "daily-report", capture(keys), any()) }
        assertThat(keys.single()).isEqualTo("stock:daily-report:2024-01-05")
    }

    @Test
    fun `마지막 거래일에 교차한 종목이 없으면 null 을 반환하고 발신하지 않는다`() {
        // given — 이전에 교차했거나, 교차 이력이 없거나, 판단 불가인 종목
        stub(
            listOf(
                row("OLD", trendSince = LocalDate.parse("2023-11-01")),
                row("NOCROSS", trendSince = null),
                row("NEW", trend = TrendStateEnum.UNKNOWN, trendSince = null),
            ),
        )

        // when
        val result = builder.run()

        // then
        assertThat(result).isNull()
        verify(exactly = 0) { notifyRouter.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `골든크로스와 데드크로스를 나눠 현재가와 50일선 200일선을 표시한다`() {
        // given
        stub(
            listOf(
                row("UPX"),
                row("DOWNX", trend = TrendStateEnum.DEAD),
                row("OLD", trendSince = LocalDate.parse("2023-11-01")),
            ),
        )

        // when
        val result = requireNotNull(builder.run())

        // then
        assertThat(result).contains(
            "🟢 골든크로스 (50일선 ↑ 200일선)\n<b>UPX</b> $100.00 (+1.2%)  50일 $98.50 · 200일 $97.25",
            "🔴 데드크로스 (50일선 ↓ 200일선)\n<b>DOWNX</b> $100.00 (+1.2%)  50일 $98.50 · 200일 $97.25",
        )
        assertThat(result).doesNotContain("OLD")
    }

    @Test
    fun `티커를 지정하면 그 티커만 본다`() {
        // given
        stub(listOf(row("AAPL"), row("MSFT")))

        // when
        val result = requireNotNull(builder.run(listOf("msft")))

        // then
        assertThat(result).contains("MSFT").doesNotContain("AAPL")
    }

    @Test
    fun `4000자 초과 리포트는 파트별 payload_key 로 발신한다`() {
        // given — 전환 100개 → 단일 텔레그램 한도(4000자) 초과 (NotifyRouter dedup 잘림 방지 #184)
        stub((0 until 100).map { row("TK%03d".format(it)) })

        // when
        val full = requireNotNull(builder.run())

        // then
        assertThat(full.length).isGreaterThan(4000)
        val keys = mutableListOf<String>()
        val messages = mutableListOf<NotifyMessage>()
        verify(atLeast = 2) {
            notifyRouter.dispatch("stock", "daily-report", capture(keys), capture(messages))
        }
        assertThat(keys[0]).endsWith(":part-1")
        assertThat(keys[1]).endsWith(":part-2")
        assertThat(messages).allMatch { it.text.length <= 4000 }
        assertThat(messages.joinToString("\n") { it.text }).contains("TK000", "TK099")
    }
}
