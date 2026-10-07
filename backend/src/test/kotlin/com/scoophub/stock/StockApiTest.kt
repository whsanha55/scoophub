package com.scoophub.stock

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.global.auth.JwtService
import com.scoophub.stock.vo.Candle
import com.scoophub.stock.vo.OptionQuote
import com.scoophub.stock.vo.OptionsChain
import com.scoophub.stock.vo.Quote
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate

/** StockController API 동작 고정 — 관심종목 CRUD, 리포트, 시그마 조회 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StockApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val jdbcClient: JdbcClient,
) {
    @MockkBean
    private lateinit var provider: AlpacaMarketDataClient

    private val bearer by lazy { "Bearer ${jwtService.create("admin@test.com", true)}" }

    @BeforeEach
    fun clean() {
        listOf(
            "stock_analysis_results",
            "stock_analysis_history",
            "stock_sigma",
            "stock_candles",
            "stock_watchlist",
        ).forEach { jdbcClient.sql("DELETE FROM $it").update() }
    }

    @Test
    fun `관심종목을 추가하면 대문자 티커로 저장하고 중복 추가는 duplicate 로 응답한다`() {
        // when & then
        mockMvc.post("/api/stock/watchlist") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticker": "aapl", "exchange": "nas", "name": "Apple"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.ticker") { value("AAPL") }
            jsonPath("$.data.exchange") { value("NAS") }
            jsonPath("$.data.is_active") { value(true) }
            jsonPath("$.data.group") { value("individual") }
        }

        mockMvc.post("/api/stock/watchlist") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticker": "AAPL"}"""
        }.andExpect {
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("duplicate") }
        }

        mockMvc.get("/api/stock/watchlist").andExpect {
            jsonPath("$.data.length()") { value(1) }
        }
    }

    @Test
    fun `지수 기호 관심종목은 추가할 수 없다`() {
        // when & then
        mockMvc.post("/api/stock/watchlist") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticker": "^IXIC"}"""
        }.andExpect {
            status { isUnprocessableEntity() }
        }
    }

    @Test
    fun `관심종목 수정은 전달한 필드만 바꾸고 빈 요청은 기존 값을 반환한다`() {
        // given
        val id = insertWatchlist("MSFT")

        // when & then
        mockMvc.put("/api/stock/watchlist/$id") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "Microsoft", "group": "market", "is_active": false}"""
        }.andExpect {
            jsonPath("$.data.ticker") { value("MSFT") }
            jsonPath("$.data.name") { value("Microsoft") }
            jsonPath("$.data.group") { value("market") }
            jsonPath("$.data.is_active") { value(false) }
        }

        mockMvc.put("/api/stock/watchlist/$id") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            jsonPath("$.data.name") { value("Microsoft") }
        }

        mockMvc.put("/api/stock/watchlist/999999") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `관심종목을 삭제하면 해당 티커의 분석 결과도 함께 삭제된다`() {
        // given
        val id = insertWatchlist("NVDA")
        insertAnalysis("NVDA")

        // when
        mockMvc.delete("/api/stock/watchlist/$id") {
            header("Authorization", bearer)
        }.andExpect {
            jsonPath("$.data.deleted") { value(id) }
        }

        // then
        val remaining = jdbcClient.sql("SELECT COUNT(*) FROM stock_analysis_results WHERE ticker = 'NVDA'")
            .query(Int::class.java)
            .single()
        assertThat(remaining).isZero()
        mockMvc.delete("/api/stock/watchlist/$id") {
            header("Authorization", bearer)
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `같은 티커의 다른 관심종목이 남아 있으면 비활성 행을 삭제해도 분석 결과는 유지된다`() {
        // given
        insertWatchlist("AMD")
        val inactiveId = insertWatchlist("AMD", active = false)
        insertAnalysis("AMD")

        // when
        mockMvc.delete("/api/stock/watchlist/$inactiveId") {
            header("Authorization", bearer)
        }.andExpect {
            jsonPath("$.data.deleted") { value(inactiveId) }
        }

        // then
        val remaining = jdbcClient.sql("SELECT COUNT(*) FROM stock_analysis_results WHERE ticker = 'AMD'")
            .query(Int::class.java)
            .single()
        assertThat(remaining).isOne()
    }

    @Test
    fun `캔들 가격과 거래량은 float4 로 깎이지 않고 그대로 저장된다`() {
        // given — float4 유효숫자(약 7자리)를 넘는 값
        jdbcClient.sql(
            "INSERT INTO stock_candles (ticker, interval, date, open, high, low, close, volume) " +
                "VALUES ('QQQ', '1D', '2026-10-06', 18234.56, 18300.12, 18100.01, 18250.78, 123456789)",
        ).update()

        // when
        val (close, volume) = jdbcClient.sql("SELECT close, volume FROM stock_candles WHERE ticker = 'QQQ'")
            .query { rs, _ -> rs.getDouble("close") to rs.getDouble("volume") }
            .single()

        // then
        assertThat(close).isEqualTo(18250.78)
        assertThat(volume).isEqualTo(123456789.0)
    }

    @Test
    fun `리포트는 관심종목 그룹을 채운다`() {
        // given
        insertWatchlist("AAPL", group = "market")
        insertAnalysis("AAPL")

        // when & then
        mockMvc.get("/api/stock/report") { param("tickers", "aapl, ") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].ticker") { value("AAPL") }
            jsonPath("$.data[0].group") { value("market") }
            jsonPath("$.data[0].technical.signal") { value("BUY") }
            jsonPath("$.data[0].is_stale") { value(false) }
        }
    }

    @Test
    fun `잘못된 timeframe 은 400 이다`() {
        mockMvc.get("/api/stock/report") {
            param("tickers", "AAPL")
            param("timeframe", "5M")
        }.andExpect { status { isBadRequest() } }

        mockMvc.get("/api/stock/report/all") { param("timeframe", "5M") }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `전체 리포트 summarize 는 요약 형태로 반환한다`() {
        // given
        insertAnalysis("AAPL")
        insertAnalysis("MSFT")

        // when & then
        mockMvc.get("/api/stock/report/all") { param("summarize", "true") }.andExpect {
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].signal") { value("BUY") }
            jsonPath("$.data[0].total_score") { value(3.5) }
        }
    }

    @Test
    fun `상세 조회는 실시간 quote 를 붙이고 분석이 없으면 data 가 null 이다`() {
        // given
        insertAnalysis("AAPL")
        every { provider.snapshots(listOf("AAPL")) } returns mapOf(
            "AAPL" to Quote(
                price = 101.0,
                change = 1.0,
                changePercent = 1.0,
                open = 100.0,
                high = 102.0,
                low = 99.0,
                volume = 0.0,
            ),
        )

        // when & then
        mockMvc.get("/api/stock/detail/aapl").andExpect {
            jsonPath("$.data.ticker") { value("AAPL") }
            jsonPath("$.data.quote.price") { value(101.0) }
            jsonPath("$.data.quote.volume") { doesNotExist() }
        }
        mockMvc.get("/api/stock/detail/NONE").andExpect {
            jsonPath("$.success") { value(true) }
            jsonPath("$.data") { doesNotExist() }
        }
    }

    @Test
    fun `시그마 조회는 최신 스냅샷을 반환하고 없으면 data 가 null 이다`() {
        // given
        jdbcClient.sql(
            """
            INSERT INTO stock_sigma
                (ticker, expiry_date, snapshot_date, snapshot_at, current_price, atm_strike,
                 atm_call, atm_put, expected_move, expected_move_pct)
            VALUES ('AAPL', DATE '2026-10-02', DATE '2026-09-30', now(), 100, 100, 3, 3, 6, 6)
            """,
        ).update()

        // when & then
        mockMvc.get("/api/stock/sigma") { param("ticker", "aapl") }.andExpect {
            jsonPath("$.data.ticker") { value("AAPL") }
            jsonPath("$.data.expected_move") { value(6.0) }
        }
        mockMvc.get("/api/stock/sigma") { param("ticker", "NONE") }.andExpect {
            jsonPath("$.data") { doesNotExist() }
        }
    }

    @Test
    fun `시그마 즉시 계산은 시세가 없으면 에러로 집계한다`() {
        // given
        every { provider.snapshots(listOf("AAPL")) } returns emptyMap()

        // when & then
        mockMvc.post("/api/crawling/stock/sigma/compute") {
            header("Authorization", bearer)
            param("tickers", "aapl")
        }.andExpect {
            jsonPath("$.data.saved") { value(0) }
            jsonPath("$.data.errors") { value(1) }
            jsonPath("$.data.tickers[0]") { value("AAPL") }
        }
    }

    @Test
    fun `관심종목이 없으면 분석과 시그마 계산은 빈 결과다`() {
        mockMvc.post("/api/crawling/stock/analyze") {
            header("Authorization", bearer)
        }.andExpect {
            jsonPath("$.data.total") { value(0) }
        }
        mockMvc.post("/api/crawling/stock/sigma/compute") {
            header("Authorization", bearer)
        }.andExpect {
            jsonPath("$.data.saved") { value(0) }
            jsonPath("$.data.tickers.length()") { value(0) }
        }
    }

    @Test
    fun `캔들 동기화는 시세 일봉을 stock_candles 에 저장한다`() {
        // given
        insertWatchlist("QQQ")
        every { provider.dailyBars(listOf("QQQ")) } returns mapOf("QQQ" to candles("QQQ", 3))

        // when & then
        mockMvc.post("/api/crawling/stock/sync") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.data.synced") { value(3) }
        }
        val count = jdbcClient.sql(
            "SELECT count(*) FROM stock_candles WHERE ticker = 'QQQ'",
        ).query(Int::class.java).single()
        assertThat(count).isEqualTo(3)
    }

    @Test
    fun `시그마 즉시 계산은 옵션 체인으로 계산한 스트래들을 저장한다`() {
        // given
        every { provider.snapshots(listOf("QQQ")) } returns mapOf("QQQ" to quote(759.66))
        every { provider.optionChains("QQQ") } returns listOf(
            OptionsChain(
                expiry = LocalDate.parse("2026-10-09"),
                calls = listOf(OptionQuote(strike = 760.0, bid = 4.25, ask = 4.39, lastPrice = 4.3, volume = 9675)),
                puts = listOf(OptionQuote(strike = 760.0, bid = 4.1, ask = 4.31, lastPrice = 4.2, volume = 24456)),
            ),
        )

        // when & then
        mockMvc.post("/api/crawling/stock/sigma/compute") {
            header("Authorization", bearer)
            param("tickers", "QQQ")
        }.andExpect {
            jsonPath("$.data.saved") { value(1) }
            jsonPath("$.data.errors") { value(0) }
        }
        val count = jdbcClient.sql(
            "SELECT count(*) FROM stock_sigma WHERE ticker = 'QQQ'",
        ).query(Int::class.java).single()
        assertThat(count).isOne()
    }

    @Test
    fun `분석 실행은 DB 일봉으로 일 주 월 분석 결과와 이력을 저장한다`() {
        // given
        insertWatchlist("QQQ")
        insertCandles(candles("QQQ", 650))
        every { provider.snapshots(listOf("QQQ")) } returns mapOf("QQQ" to quote(759.66))

        // when & then
        mockMvc.post("/api/crawling/stock/analyze") {
            header("Authorization", bearer)
            param("tickers", "QQQ")
        }.andExpect {
            jsonPath("$.data.ok") { value(1) }
        }
        val timeframes = jdbcClient.sql("SELECT timeframe FROM stock_analysis_results WHERE ticker = 'QQQ'")
            .query(String::class.java)
            .list()
        assertThat(timeframes).containsExactlyInAnyOrder("1D", "1W", "1M")
        val historyTimeframes = jdbcClient.sql("SELECT timeframe FROM stock_analysis_history WHERE ticker = 'QQQ'")
            .query(String::class.java)
            .list()
        assertThat(historyTimeframes).containsExactlyInAnyOrder("1D", "1W", "1M")
    }

    @Test
    fun `같은 거래일에 다시 분석하면 이력은 그날 행을 갱신한다`() {
        // given
        insertWatchlist("QQQ")
        insertCandles(candles("QQQ", 130))
        every { provider.snapshots(listOf("QQQ")) } returns mapOf("QQQ" to quote(759.66))
        mockMvc.post("/api/crawling/stock/analyze") {
            header("Authorization", bearer)
            param("tickers", "QQQ")
        }
        every { provider.snapshots(listOf("QQQ")) } returns mapOf("QQQ" to quote(800.0))

        // when
        mockMvc.post("/api/crawling/stock/analyze") {
            header("Authorization", bearer)
            param("tickers", "QQQ")
        }

        // then
        val prices = jdbcClient.sql(
            "SELECT price FROM stock_analysis_history WHERE ticker = 'QQQ' AND timeframe = '1D'",
        )
            .query(Double::class.java)
            .list()
        assertThat(prices).containsExactly(800.0)
    }

    @Test
    fun `DB 에 일봉이 없으면 분석은 에러로 집계한다`() {
        // given
        insertWatchlist("QQQ")
        every { provider.snapshots(listOf("QQQ")) } returns mapOf("QQQ" to quote(759.66))

        // when & then
        mockMvc.post("/api/crawling/stock/analyze") {
            header("Authorization", bearer)
            param("tickers", "QQQ")
        }.andExpect {
            jsonPath("$.data.ok") { value(0) }
            jsonPath("$.data.errors") { value(1) }
        }
    }

    private fun quote(price: Double) =
        Quote(price = price, change = 1.0, changePercent = 0.1, open = price, high = price, low = price, volume = 1.0)

    private fun candles(ticker: String, days: Int): List<Candle> = (0 until days).map { i ->
        val close = 700.0 + i
        Candle(
            ticker = ticker,
            interval = "1D",
            date = LocalDate.parse("2026-04-01").plusDays(i.toLong()),
            open = close - 1,
            high = close + 2,
            low = close - 2,
            close = close,
            volume = 1_000_000.0 + i,
        )
    }

    private fun insertCandles(candles: List<Candle>) {
        candles.forEach { c ->
            jdbcClient.sql(
                "INSERT INTO stock_candles (ticker, interval, date, open, high, low, close, volume) " +
                    "VALUES (:ticker, :interval, :date, :open, :high, :low, :close, :volume)",
            )
                .param("ticker", c.ticker)
                .param("interval", c.interval)
                .param("date", c.date)
                .param("open", c.open)
                .param("high", c.high)
                .param("low", c.low)
                .param("close", c.close)
                .param("volume", c.volume)
                .update()
        }
    }

    private fun insertWatchlist(ticker: String, group: String = "individual", active: Boolean = true): Int =
        jdbcClient.sql(
            "INSERT INTO stock_watchlist (ticker, exchange, name, is_active, \"group\") " +
                "VALUES (:ticker, 'NAS', '', :active, :group) RETURNING id",
        )
            .param("ticker", ticker)
            .param("group", group)
            .param("active", active)
            .query(Int::class.java)
            .single()

    private fun insertAnalysis(ticker: String) {
        jdbcClient.sql(
            """
            INSERT INTO stock_analysis_results
                (ticker, exchange, timeframe, signal, total_score, confidence, market_regime,
                 price, change, change_rate, technical_scores, technical_details, analyzed_at)
            VALUES (:ticker, 'NAS', '1D', 'BUY', 3.5, 65, 'RANGING', 100, 1, 1.2,
                    '{"rsi": 10}'::jsonb, '{"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.3}'::jsonb, now())
            """,
        )
            .param("ticker", ticker)
            .update()
    }
}
