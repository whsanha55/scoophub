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
import java.time.ZoneOffset

/**
 * legacy tests/test_stock_report.py T5(ReportBuilder.run) 포팅 — repository/router 만 목킹한
 * 순수 단위 테스트(Spring 컨텍스트 없음).
 */
class StockReportBuilderTest {

    private val analysisRepository = mockk<StockAnalysisResultRepository>()
    private val watchlistRepository = mockk<StockWatchlistRepository>()
    private val notifyRouter = mockk<NotifyRouter>(relaxUnitFun = true)
    private val clock = Clock.fixed(Instant.parse("2024-01-05T10:15:00Z"), ZoneOffset.UTC)
    private val builder = StockReportBuilder(analysisRepository, watchlistRepository, notifyRouter, clock)
    private val jsonMapper = JsonMapper.builder().build()

    private fun row(ticker: String, price: Double = 100.0, signal: String = "BUY") = StockAnalysisResultEntity(
        ticker = ticker,
        exchange = "NAS",
        timeframe = "1D",
        signal = signal,
        totalScore = 3.5,
        confidence = 65.0,
        marketRegime = "RANGING",
        price = price,
        change = 1.0,
        changeRate = 1.2,
        technicalScores = jsonMapper.readTree("""{"rsi": 10}"""),
        technicalDetails = jsonMapper.readTree(
            """{"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.3,
                "sigma_data": {"straddle": {"expected_move": 10.0}}}""",
        ),
        analyzedAt = Instant.parse("2024-01-05T10:00:00Z"),
    )

    private fun watchlistItem(ticker: String, group: String) =
        StockWatchlistEntity(ticker = ticker, group = group, addedAt = Instant.parse("2024-01-01T00:00:00Z"))

    private fun stubGroups(groups: Map<String, List<String>>) {
        for (grp in listOf("market", "sector", "individual")) {
            every { watchlistRepository.findByIsActiveAndGroupOrderByAddedAt(true, grp) } returns
                groups[grp].orEmpty().map { watchlistItem(it, grp) }
        }
    }

    private fun stubRows(rows1d: List<StockAnalysisResultEntity>) {
        every { analysisRepository.findByTickerInAndTimeframeOrderByTotalScoreDesc(any(), any()) } answers {
            if (secondArg<String>() == "1D") rows1d else emptyList()
        }
    }

    @Test
    fun `단일 그룹 리포트를 발신하고 payload_key 에 part 접미사가 없다`() {
        // given
        stubGroups(mapOf("individual" to listOf("AAPL")))
        stubRows(listOf(row("AAPL")))

        // when
        val result = builder.run()

        // then
        assertThat(result).isNotNull()
        assertThat(result).contains("AAPL", "개별종목", "Scoophub에서 보기")
        val keys = mutableListOf<String>()
        val messages = mutableListOf<NotifyMessage>()
        verify(exactly = 1) {
            notifyRouter.dispatch("stock", "daily-report", capture(keys), capture(messages))
        }
        assertThat(keys.single()).startsWith("stock:daily-report:2024-01-05") // KST 기준 날짜
        assertThat(keys.single()).doesNotContain(":part-") // 단일 메시지는 suffix 없음
        assertThat(messages.single().text).contains("개별종목")
    }

    @Test
    fun `분석 데이터가 없으면 null 을 반환하고 발신하지 않는다`() {
        // given
        stubGroups(mapOf("individual" to listOf("AAPL")))
        stubRows(emptyList())

        // when
        val result = builder.run()

        // then
        assertThat(result).isNull()
        verify(exactly = 0) { notifyRouter.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `여러 그룹이 있으면 시장 섹터 개별 블록을 모두 포함한다`() {
        // given
        stubGroups(
            mapOf(
                "market" to listOf("QQQ"),
                "sector" to listOf("XLK"),
                "individual" to listOf("AAPL"),
            ),
        )
        stubRows(listOf(row("QQQ", price = 750.0), row("XLK", price = 200.0), row("AAPL")))

        // when
        val result = builder.run()

        // then
        assertThat(result).isNotNull()
        assertThat(result).contains("시장층", "섹터층", "개별종목", "QQQ", "XLK", "AAPL")
    }

    @Test
    fun `4000자 초과 리포트는 파트별 payload_key 로 발신한다`() {
        // given — 티커 40개 → 단일 텔레그램 한도(4000자) 초과 (NotifyRouter dedup 잘림 방지 #184)
        val tickers = (0 until 40).map { "TK%03d".format(it) }
        stubGroups(mapOf("individual" to tickers))
        stubRows(tickers.map { row(it) })

        // when
        val result = builder.run()

        // then
        val full = requireNotNull(result)
        assertThat(full.length).isGreaterThan(4000)
        val keys = mutableListOf<String>()
        val messages = mutableListOf<NotifyMessage>()
        verify(atLeast = 2) {
            notifyRouter.dispatch("stock", "daily-report", capture(keys), capture(messages))
        }
        assertThat(keys).allMatch { it.contains(":part-") }
        assertThat(keys[0]).endsWith(":part-1")
        assertThat(keys[1]).endsWith(":part-2")
        assertThat(keys[0]).isNotEqualTo(keys[1])
        assertThat(messages).allMatch { it.text.length <= 4000 }
        assertThat(messages.joinToString("\n") { it.text }).contains("TK000", "TK039")
    }
}
