package com.scoophub.stock

import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.stock.dto.SigmaBandOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockWatchlistEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.service.StockSigmaService
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

/** 추세 전환·σ 리포트 — repository/service/router 만 목킹한 순수 단위 테스트(Spring 컨텍스트 없음) */
class StockReportBuilderTest {

    private val analysisRepository = mockk<StockAnalysisResultRepository>()
    private val watchlistRepository = mockk<StockWatchlistRepository>()
    private val sigmaService = mockk<StockSigmaService>()
    private val notifyRouter = mockk<NotifyRouter>(relaxUnitFun = true)
    private val clock = Clock.fixed(Instant.parse("2024-01-05T10:15:00Z"), ZoneOffset.UTC)
    private val builder =
        StockReportBuilder(analysisRepository, watchlistRepository, sigmaService, notifyRouter, clock)
    private val jsonMapper = JsonMapper.builder().build()

    /** 2024-01-04 는 목요일 */
    private val lastCandle = LocalDate.parse("2024-01-04")
    private val old = LocalDate.parse("2023-11-01")

    private fun row(
        ticker: String,
        trend: TrendStateEnum = TrendStateEnum.GOLDEN,
        trendSince: LocalDate? = lastCandle,
        changeRate: Double = 1.2,
    ) = StockAnalysisResultEntity(
        ticker = ticker,
        exchange = "NAS",
        timeframe = "1D",
        trend = trend.name,
        trendSince = trendSince,
        candleDate = lastCandle,
        price = 100.0,
        change = 1.0,
        changeRate = changeRate,
        technicalDetails = jsonMapper.readTree("""{"sma50": 98.5, "sma200": 97.25}"""),
        analyzedAt = Instant.parse("2024-01-05T10:00:00Z"),
    )

    private fun sigma(position: Double) = SigmaBandOut.of(
        SigmaBand(
            snapshotDate = LocalDate.parse("2023-12-29"),
            expiryDate = LocalDate.parse("2024-01-05"),
            anchorPrice = 100.0 - position * 4.0,
            expectedMove = 4.0,
        ),
        close = 100.0,
        asOf = lastCandle,
    )

    private fun stub(
        rows: List<StockAnalysisResultEntity>,
        sigmas: Map<String, SigmaBandOut> = emptyMap(),
        previous: Map<String, SigmaStateEnum> = emptyMap(),
        groups: Map<String, String> = emptyMap(),
    ) {
        every { sigmaService.findCurrentBands(any()) } answers {
            val tickers = firstArg<List<StockAnalysisResultEntity>>().map { it.ticker }
            sigmas.filterKeys { it in tickers }
        }
        every { sigmaService.findPreviousStates(any()) } returns previous
        every { watchlistRepository.findByIsActiveOrderByAddedAt() } returns rows.map {
            StockWatchlistEntity(
                ticker = it.ticker,
                group = groups[it.ticker] ?: "individual",
                addedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }
        every { analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(any(), "1D") } answers {
            val tickers = firstArg<List<String>>()
            rows.filter { it.ticker in tickers }
        }
    }

    private fun dispatched(): List<Pair<String, String>> {
        val keys = mutableListOf<String>()
        val messages = mutableListOf<NotifyMessage>()
        verify { notifyRouter.dispatch("stock", "daily-report", capture(keys), capture(messages)) }
        return keys.zip(messages.map { it.text })
    }

    @Test
    fun `신규 메시지 헤더에 마감일 요일과 밴드 만기, 시장 한 줄을 싣는다`() {
        // given
        stub(
            listOf(row("AAPL"), row("SPY", trendSince = old, changeRate = -0.8), row("QQQ", trendSince = old)),
            sigmas = mapOf("SPY" to sigma(-0.3), "QQQ" to sigma(0.5)),
        )

        // when
        val result = requireNotNull(builder.run())

        // then
        assertThat(result).contains(
            "<b>📊 주식 리포트</b> · 01/04(목) 마감 · 밴드 만기 01/05",
            "시장  SPY -0.30σ (-0.8%) · QQQ +0.50σ (+1.2%)",
            "🔗 Scoophub에서 전체 보기",
        )
        val sent = dispatched()
        assertThat(sent.map { it.first }).containsExactly("stock:daily-report:2024-01-05")
    }

    @Test
    fun `크로스와 신규 이탈이 없으면 발신하지 않는다`() {
        // given — 예전에 교차했고 밴드 안
        stub(
            listOf(row("OLD", trendSince = old), row("NOCROSS", trendSince = null)),
            sigmas = mapOf("OLD" to sigma(0.4)),
        )

        // when
        val result = builder.run()

        // then
        assertThat(result).isNull()
        verify(exactly = 0) { notifyRouter.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `골든크로스와 데드크로스 줄은 상세 링크·테마·종가·등락·50일선 200일선이다`() {
        // given
        stub(
            listOf(row("UPX"), row("DOWNX", trend = TrendStateEnum.DEAD, changeRate = -2.35)),
            groups = mapOf("UPX" to "semiconductor", "DOWNX" to "ai_infra"),
        )

        // when
        val result = requireNotNull(builder.run())

        // then
        assertThat(result).contains(
            "🟢 골든크로스 (50일선 ↑ 200일선)\n" +
                "<a href=\"https://scoophub.gonamu.com/stock/UPX\">UPX</a> · 반도체  $100.00  +1.2%  50일 $98.50 · 200일 $97.25",
            "🔴 데드크로스 (50일선 ↓ 200일선)\n" +
                "<a href=\"https://scoophub.gonamu.com/stock/DOWNX\">DOWNX</a> · AI 인프라  $100.00  -2.4%  50일 $98.50 · 200일 $97.25",
        )
    }

    @Test
    fun `오늘 처음 밴드를 벗어난 종목은 신규, 전 거래일에도 같은 방향이면 지속 메시지로 나눈다`() {
        // given
        stub(
            listOf(
                row("NEWUP", trendSince = old),
                row("NEWDOWN", trendSince = old, changeRate = -9.7),
                row("STILLUP", trendSince = old),
                row("FLIP", trendSince = old),
                row("FLAT", trendSince = old),
            ),
            sigmas = mapOf(
                "NEWUP" to sigma(1.25),
                "NEWDOWN" to sigma(-1.45),
                "STILLUP" to sigma(1.6),
                "FLIP" to sigma(-1.1),
                "FLAT" to sigma(0.4),
            ),
            previous = mapOf(
                "NEWUP" to SigmaStateEnum.NORMAL,
                "STILLUP" to SigmaStateEnum.UPPER_BREAK,
                "FLIP" to SigmaStateEnum.UPPER_BREAK,
            ),
            groups = mapOf("NEWDOWN" to "ai_infra"),
        )

        // when
        builder.run()

        // then
        val sent = dispatched()
        assertThat(sent.map { it.first })
            .containsExactly("stock:daily-report:2024-01-05", "stock:daily-report:2024-01-05:ongoing")
        val (newText, ongoingText) = sent.map { it.second }
        assertThat(newText).contains(
            "⬆️ +1σ 상단 돌파 · 신규\n🆕 <a href=\"https://scoophub.gonamu.com/stock/NEWUP\">NEWUP</a> · 기타  $100.00  +1.2%  +1.25σ",
            "⬇️ −1σ 하단 이탈 · 신규\n" +
                "🆕 <a href=\"https://scoophub.gonamu.com/stock/NEWDOWN\">NEWDOWN</a> · AI 인프라  $100.00  -9.7%  -1.45σ",
            "FLIP",
        )
        assertThat(newText).doesNotContain("STILLUP", "FLAT")
        assertThat(ongoingText).contains(
            "<b>📌 밴드 밖 지속</b> · 01/04(목) 마감",
            "⬆️ <a href=\"https://scoophub.gonamu.com/stock/STILLUP\">STILLUP</a> · 기타  $100.00  +1.2%  +1.60σ",
        )
        assertThat(ongoingText).doesNotContain("NEWUP", "NEWDOWN", "FLIP", "FLAT")
    }

    @Test
    fun `지속 종목만 있으면 지속 메시지만 보낸다`() {
        // given
        stub(
            listOf(row("STILL", trendSince = old)),
            sigmas = mapOf("STILL" to sigma(-1.2)),
            previous = mapOf("STILL" to SigmaStateEnum.LOWER_BREAK),
        )

        // when
        val result = requireNotNull(builder.run())

        // then
        assertThat(result).contains("📌 밴드 밖 지속", "STILL").doesNotContain("📊 주식 리포트")
        assertThat(dispatched().map { it.first }).containsExactly("stock:daily-report:2024-01-05:ongoing")
    }

    @Test
    fun `티커를 지정하면 그 티커만 보되 시장 한 줄은 유지한다`() {
        // given
        stub(listOf(row("AAPL"), row("MSFT"), row("SPY", trendSince = old)), sigmas = mapOf("SPY" to sigma(0.2)))

        // when
        val result = requireNotNull(builder.run(listOf("msft")))

        // then
        assertThat(result).contains("MSFT", "시장  SPY +0.20σ").doesNotContain("AAPL")
    }

    @Test
    fun `4000자 초과 리포트는 파트별 payload_key 로 발신한다`() {
        // given — 전환 100개 → 단일 텔레그램 한도(4000자) 초과 (NotifyRouter dedup 잘림 방지 #184)
        stub((0 until 100).map { row("TK%03d".format(it)) })

        // when
        requireNotNull(builder.run())

        // then
        val sent = dispatched()
        assertThat(sent.size).isGreaterThanOrEqualTo(2)
        assertThat(sent[0].first).endsWith(":part-1")
        assertThat(sent[1].first).endsWith(":part-2")
        assertThat(sent).allMatch { it.second.length <= 4000 }
        assertThat(sent.joinToString("\n") { it.second }).contains("TK000", "TK099")
    }
}
