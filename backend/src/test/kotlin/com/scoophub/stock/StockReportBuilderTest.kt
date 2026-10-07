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

    // price 100, σ ±10, ATR 4, 불타기 진입 → 목표 110, 손절 94 (100 - 1.5×4), 볼린저 하단 92.5
    private fun row(
        ticker: String,
        signal: String = "BUY",
        confidence: Double = 65.0,
        timeframe: String = "1D",
        price: Double = 100.0,
    ) = StockAnalysisResultEntity(
        ticker = ticker,
        exchange = "NAS",
        timeframe = timeframe,
        signal = signal,
        totalScore = 3.5,
        confidence = confidence,
        marketRegime = "RANGING",
        price = price,
        change = 1.0,
        changeRate = 1.2,
        technicalScores = jsonMapper.readTree("""{"rsi": 10}"""),
        technicalDetails = jsonMapper.readTree(
            """{"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.3, "bb_lower": 92.5,
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

    /** 1D 행을 주고, 1W 는 weekly 맵(ticker → signal)으로 만든다. 지정 없으면 1D 와 같은 신호 */
    private fun stubRows(rows1d: List<StockAnalysisResultEntity>, weekly: Map<String, String> = emptyMap()) {
        every { analysisRepository.findByTickerInAndTimeframeOrderByTotalScoreDesc(any(), any()) } answers {
            val tickers = firstArg<List<String>>()
            val target = rows1d.filter { it.ticker in tickers }
            when (secondArg<String>()) {
                "1D" -> target
                "1W" -> target.map { row(it.ticker, signal = weekly[it.ticker] ?: it.signal, timeframe = "1W") }
                else -> emptyList()
            }
        }
    }

    @Test
    fun `단일 메시지 리포트를 발신하고 payload_key 에 part 접미사가 없다`() {
        // given
        stubGroups(mapOf("individual" to listOf("AAPL")))
        stubRows(listOf(row("AAPL")))

        // when
        val result = builder.run()

        // then
        assertThat(result).isNotNull()
        assertThat(result).contains("주식 일간 신호", "개별종목", "AAPL", "Scoophub에서 전체 보기")
        val keys = mutableListOf<String>()
        val messages = mutableListOf<NotifyMessage>()
        verify(exactly = 1) {
            notifyRouter.dispatch("stock", "daily-report", capture(keys), capture(messages))
        }
        assertThat(keys.single()).startsWith("stock:daily-report:2024-01-05") // KST 기준 날짜
        assertThat(keys.single()).doesNotContain(":part-") // 단일 메시지는 suffix 없음
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
    fun `1D 1W 방향이 같고 신뢰도 60 이상인 종목만 고른다`() {
        // given
        stubGroups(mapOf("individual" to listOf("PICK", "WEEKOFF", "LOWCONF", "HOLDX")))
        stubRows(
            listOf(
                row("PICK", signal = "BUY"),
                row("WEEKOFF", signal = "BUY"),
                row("LOWCONF", signal = "BUY", confidence = 59.9),
                row("HOLDX", signal = "HOLD"),
            ),
            weekly = mapOf("PICK" to "STRONG_BUY", "WEEKOFF" to "SELL"),
        )

        // when
        val result = requireNotNull(builder.run())

        // then — BUY 와 STRONG_BUY 는 같은 방향
        assertThat(result).contains("PICK")
        assertThat(result).doesNotContain("WEEKOFF", "LOWCONF", "HOLDX")
    }

    @Test
    fun `매수는 목표 손절, 매도는 재진입가를 표시하고 신뢰도와 STRONG 표기는 없다`() {
        // given
        stubGroups(mapOf("individual" to listOf("UPX", "DOWNX")))
        stubRows(listOf(row("UPX", signal = "STRONG_BUY", confidence = 84.0), row("DOWNX", signal = "SELL")))

        // when
        val result = requireNotNull(builder.run())

        // then
        assertThat(result).contains(
            "🟢 매수\n<b>UPX</b> $100.00 (+1.2%)  목표 $110.00 · 손절 $94.00",
            "🔴 매도\n<b>DOWNX</b> $100.00 (+1.2%)  재진입 $92.50",
        )
        assertThat(result).doesNotContain("신뢰도", "STRONG", "84")
    }

    @Test
    fun `섹터와 개별종목을 나누고 신호 없는 그룹은 한 줄로 알린다`() {
        // given
        stubGroups(
            mapOf(
                "market" to listOf("SPY", "QQQ"),
                "sector" to listOf("XLK"),
                "individual" to listOf("AAPL"),
            ),
        )
        stubRows(
            listOf(
                row("SPY", signal = "STRONG_SELL"),
                row("QQQ", signal = "HOLD"),
                row("XLK", signal = "HOLD"),
                row("AAPL", signal = "BUY"),
            ),
        )

        // when
        val result = requireNotNull(builder.run())

        // then
        assertThat(result).contains("시장: SPY SELL · QQQ HOLD")
        assertThat(result).contains("<b>🏭 섹터</b>\n강한 신호 없음")
        assertThat(result).contains("<b>📈 개별종목</b>\n🟢 매수\n<b>AAPL</b>")
    }

    @Test
    fun `4000자 초과 리포트는 파트별 payload_key 로 발신한다`() {
        // given — 매수 신호 100개 → 단일 텔레그램 한도(4000자) 초과 (NotifyRouter dedup 잘림 방지 #184)
        val tickers = (0 until 100).map { "TK%03d".format(it) }
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
        assertThat(messages).allMatch { it.text.length <= 4000 }
        assertThat(messages.joinToString("\n") { it.text }).contains("TK000", "TK099")
    }
}
