package com.scoophub.news

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.llm.LlmClient
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.news.repository.NewsArticleQueryRepository
import com.scoophub.news.service.NewsArticleService
import com.scoophub.news.service.NewsArticleWorker
import com.scoophub.news.vo.AlpacaArticleRow
import com.scoophub.news.vo.ArticleAssessment
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.time.Duration
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AlpacaNewsTest @Autowired constructor(
    private val repository: NewsArticleQueryRepository,
    private val service: NewsArticleService,
    private val worker: NewsArticleWorker,
    private val jdbc: JdbcClient,
    private val mvc: MockMvc,
    private val clock: Clock,
) {
    @MockkBean private lateinit var llm: LlmClient

    @MockkBean private lateinit var router: NotifyRouter

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM news_article").update()
        jdbc.sql("DELETE FROM notify_log WHERE payload_key LIKE 'news:%'").update()
        jdbc.sql("DELETE FROM stock_watchlist").update()
        every { router.dispatchBatch(any(), any(), any()) } returns true
        every { router.dispatchConfirmed(any(), any(), any(), any()) } returns true
    }

    private fun article(
        id: Long = 1,
        symbol: String = "NVDA",
        published: Instant = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
    ): AlpacaArticleRow = AlpacaArticleRow(
        id = id,
        source = "benzinga",
        headline = "Company event $id",
        summary = "Original English summary",
        content = "<p>content</p>",
        author = "Benzinga", url = "https://example.com/$id",
        symbols = listOf(
            symbol,
        ),
        publishedAt = published, sourceUpdatedAt = published,
    )

    private fun row(id: Long = 1): AlpacaArticleRow = requireNotNull(repository.findById(id))
    private fun eligible(id: Long = 1) {
        jdbc.sql(
            "UPDATE news_article SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = :id",
        ).param("id", id).update()
    }
    private fun response(ids: List<Long>, score: Int = 4): String = ids.joinToString(",", "[", "]") {
        """{"id":$it,"importance":$score,"category":"기업","summary_ko":"한국어 요약 $it"}"""
    }

    @Test
    fun `수정 기사는 원문만 바꾸고 처리 상태와 최초 발행 시각을 보존한다`() {
        // given
        val original = article()
        service.receive(original)
        repository.saveAssessment(1, ArticleAssessment(4, "실적", "한국어 요약"))
        repository.decide(1, "pushed", "watchlist:NVDA", clock.instant())
        val firstReceived = row().createdAt
        // when
        service.receive(
            original.copy(
                headline = "Updated",
                publishedAt = original.publishedAt.plusSeconds(10),
                sourceUpdatedAt = original.sourceUpdatedAt.plusSeconds(20),
            ),
        )
        service.receive(original.copy(headline = "Older revision"))
        // then
        assertThat(row().headline).isEqualTo("Updated")
        assertThat(row().publishedAt).isEqualTo(original.publishedAt)
        assertThat(row().createdAt).isEqualTo(firstReceived)
        assertThat(row().status).isEqualTo("pushed")
        assertThat(row().summaryKo).isEqualTo("한국어 요약")
        assertThat(repository.findPending(clock.instant())).isEmpty()
    }

    @Test
    fun `목록은 발행 시각으로 정렬하고 종목 필터와 페이지를 적용한다`() {
        // given
        service.receive(article(5_000_000_001, published = clock.instant().minusSeconds(120)))
        service.receive(article(2, published = clock.instant().minusSeconds(30)))
        service.receive(article(3, "AAPL", clock.instant().minusSeconds(60)))
        // when & then
        mvc.get("/api/news") {
            param("limit", "1")
            param("page", "2")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(3) }
            jsonPath("$.meta.total") { value(3) }
        }
        mvc.get("/api/news") { param("symbol", "nvda") }.andExpect {
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].id") { value(2) }
        }
        mvc.get("/api/news/5000000001").andExpect { jsonPath("$.data.headline") { value("Company event 5000000001") } }
        mvc.get("/api/news/9999").andExpect { status { isNotFound() } }
        mvc.get("/api/news") { param("limit", "0") }.andExpect { status { isBadRequest() } }
        mvc.get("/api/news") { param("page", "0") }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `20건 넘는 적체를 같은 회차에 모두 처리하고 발행 순서를 유지한다`() {
        // given
        (1L..25L).reversed().forEach { service.receive(article(it, "SPY", clock.instant().minusSeconds(60 - it))) }
        every { llm.chatNews(any(), any()) } answers {
            val ids = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                secondArg<String>(),
            ).path("articles").toList().map {
                it.path("id").asLong()
            }
            response(ids)
        }
        val keys = mutableListOf<Long>()
        every { router.dispatchBatch(any(), any(), any()) } answers {
            keys +=
                thirdArg<List<Pair<String, com.scoophub.global.notify.NotifyMessage>>>().map {
                    it.first.substringAfterLast(':').toLong()
                }
            true
        }
        // when
        worker.processPending()
        // then
        assertThat(repository.findPending(clock.instant())).isEmpty()
        assertThat(keys).containsExactlyElementsOf((1L..25L).toList())
        verify(exactly = 2) { llm.chatNews(any(), any()) }
    }

    @Test
    fun `잡음과 오래된 기사는 LLM 없이 저장 상태만 바꾼다`() {
        // given
        service.receive(article().copy(headline = "Stocks To Watch Today"))
        service.receive(article(2, published = clock.instant().minus(Duration.ofMinutes(16))))
        service.receive(article(3).copy(headline = "Why Is Super Micro Computer Stock Trading Higher Today?"))
        service.receive(
            article(4).copy(headline = "Shares of software companies are trading lower after yields spiked"),
        )
        service.receive(article(5).copy(headline = "Stock Market Today: S&P 500 Falls"))
        service.receive(article(6).copy(headline = "Webull Stock Just Lost 20%. Here Are the Next 3 Catalysts"))
        // when
        worker.processPending()
        // then
        assertThat(row().status).isEqualTo("filtered")
        assertThat(row(2).decisionReason).isEqualTo("stale")
        assertThat((3L..6L).map { row(it).status }).containsOnly("filtered")
        verify(exactly = 0) { llm.chatNews(any(), any()) }
    }

    @Test
    fun `발송 실패 재시도는 저장한 요약을 재사용하고 성공 후 pushed로 바꾼다`() {
        // given
        service.receive(article())
        every { llm.chatNews(any(), any()) } returns response(listOf(1))
        every { router.dispatchBatch(any(), any(), any()) } returns false
        // when
        worker.processPending()
        // then
        assertThat(row().status).isEqualTo("pending")
        assertThat(row().attempts).isEqualTo(1)
        assertThat(row().summaryKo).isEqualTo("한국어 요약 1")
        // when
        eligible()
        every { router.dispatchBatch(any(), any(), any()) } returns true
        worker.processPending()
        // then
        assertThat(row().status).isEqualTo("pushed")
        verify(exactly = 1) { llm.chatNews(any(), any()) }
    }

    @Test
    fun `실패 배치가 후속 기사를 막지 않고 세 번 실패한 관심 종목은 헤드라인을 보낸다`() {
        // given
        jdbc.sql("INSERT INTO stock_watchlist (ticker, exchange, name) VALUES ('NVDA', 'NASDAQ', 'Nvidia')").update()
        service.receive(article())
        every { llm.chatNews(any(), any()) } throws IllegalStateException("Unavailable")
        // when
        worker.processPending()
        service.receive(article(2, "AAPL"))
        every { llm.chatNews(any(), any()) } returns response(listOf(2))
        worker.processPending()
        // then
        assertThat(row(2).status).isEqualTo("pushed")
        assertThat(row().status).isEqualTo("pending")
        // when
        every { llm.chatNews(any(), any()) } throws IllegalStateException("Unavailable")
        repeat(2) {
            eligible()
            worker.processPending()
        }
        // then
        assertThat(row().status).isEqualTo("failed")
        assertThat(row().attempts).isEqualTo(3)
        assertThat(row().decisionReason).isEqualTo("llm:exhausted:headline-pushed")
        verify(exactly = 1) { router.dispatchConfirmed("news", "alpaca", "news:alpaca:1", any()) }
    }

    @Test
    fun `관심 종목은 중요도 3부터 일반 종목은 4부터 발송한다`() {
        // given
        jdbc.sql("INSERT INTO stock_watchlist (ticker, exchange, name) VALUES ('NVDA', 'NASDAQ', 'Nvidia')").update()
        service.receive(article())
        service.receive(article(2, "AAPL"))
        every { llm.chatNews(any(), any()) } returns response(listOf(1, 2), 3)
        // when
        worker.processPending()
        // then
        assertThat(row().status).isEqualTo("pushed")
        assertThat(row(2).status).isEqualTo("skipped")
    }

    @Test
    fun `지수 ETF만 겹치는 관심 종목 기사는 일반 기준을 따른다`() {
        // given
        jdbc.sql("INSERT INTO stock_watchlist (ticker, exchange, name) VALUES ('SPY', 'NYSE', 'S&P 500 ETF')").update()
        service.receive(article(1, "SPY"))
        every { llm.chatNews(any(), any()) } returns response(listOf(1), 3)
        // when
        worker.processPending()
        // then
        assertThat(row().status).isEqualTo("skipped")
    }

    @Test
    fun `카드는 한국어 요약을 굵은 제목으로 쓰고 영어 헤드라인을 뺀다`() {
        // given
        service.receive(article())
        every { llm.chatNews(any(), any()) } returns response(listOf(1))
        val cards = mutableListOf<com.scoophub.global.notify.NotifyMessage>()
        every { router.dispatchBatch(any(), any(), any()) } answers {
            cards += thirdArg<List<Pair<String, com.scoophub.global.notify.NotifyMessage>>>().map { it.second }
            true
        }
        // when
        worker.processPending()
        // then
        assertThat(cards.single().text).startsWith("<b>한국어 요약 1</b>").doesNotContain("Company event")
            .contains("<a href=\"https://example.com/1\">원문</a>")
    }

    @Test
    fun `최근 발송 요약을 LLM에 넘기고 중복 판정 기사는 보내지 않는다`() {
        // given
        service.receive(article())
        repository.saveAssessment(1, ArticleAssessment(4, "기업", "울프스피드 국방부 대출"))
        repository.decide(1, "pushed", "importance score=4", clock.instant())
        service.receive(article(2))
        every { llm.chatNews(any(), any()) } returns
            """[{"id":2,"importance":4,"category":"기업","summary_ko":"울프스피드 대출 확정","duplicate":true}]"""
        // when
        worker.processPending()
        // then
        assertThat(row(2).status).isEqualTo("skipped")
        assertThat(row(2).decisionReason).isEqualTo("duplicate")
        verify { llm.chatNews(any(), match { it.contains("\"recently_sent\":[\"울프스피드 국방부 대출\"]") }) }
        verify(exactly = 0) { router.dispatchBatch(any(), any(), any()) }
    }

    @Test
    fun `급증은 중복 수정은 세지 않고 세 번째 기사부터 알린다`() {
        // given
        service.receive(article(1))
        service.receive(article(2))
        every { llm.chatNews(any(), any()) } returns response(listOf(1, 2), 3)
        worker.processPending()
        service.receive(article(2).copy(sourceUpdatedAt = clock.instant().plusSeconds(1)))
        worker.processPending()
        verify(exactly = 0) { router.dispatchConfirmed(any(), any(), any(), any()) }
        // when
        service.receive(article(3))
        every { llm.chatNews(any(), any()) } returns response(listOf(3), 3)
        worker.processPending()
        // then — mocked delivery writes no log, so verify the threshold card rather than cooldown here.
        verify {
            router.dispatchConfirmed(
                "news",
                "alpaca",
                match { it.startsWith("news:burst:NVDA:") },
                match { it.text.contains("• 한국어 요약 3") && !it.text.contains("Company event") },
            )
        }
        assertThat(repository.findBurstSymbols(clock.instant().minusSeconds(1800), clock.instant(), 3)).contains("NVDA")
    }

    @Test
    fun `급증의 실제 두 시간 쿨다운은 버킷 경계에서도 유지한다`() {
        // given
        val now = clock.instant()
        (1L..3L).forEach { service.receive(article(it)) }
        every { llm.chatNews(any(), any()) } returns response(listOf(1, 2, 3), 3)
        jdbc.sql(
            "INSERT INTO notify_routes (category, purpose, channel, chat_id) VALUES ('burst-test', '', 'telegram', 'test') ON CONFLICT DO NOTHING",
        ).update()
        val route = jdbc.sql(
            "SELECT id FROM notify_routes WHERE category = 'burst-test'",
        ).query(Long::class.java).single()
        val previousKey = "news:burst:NVDA:${now.epochSecond / 7200 - 1}"
        jdbc.sql(
            "INSERT INTO notify_log (route_id, payload_key, status, sent_at) VALUES (:route, :key, 'success', NOW() - INTERVAL '30 minutes')",
        )
            .param("route", route).param("key", previousKey).update()
        // when
        worker.processPending()
        // then
        verify(exactly = 0) { router.dispatchConfirmed(any(), any(), any(), any()) }
        // when
        jdbc.sql("UPDATE notify_log SET sent_at = NOW() - INTERVAL '121 minutes' WHERE payload_key = :key")
            .param("key", previousKey).update()
        worker.processPending()
        // then
        verify { router.dispatchConfirmed("news", "alpaca", match { it.startsWith("news:burst:NVDA:") }, any()) }
    }

    @Test
    fun `급증은 중요도 낮은 기사를 세지 않는다`() {
        // given
        (1L..3L).forEach { service.receive(article(it)) }
        every { llm.chatNews(any(), any()) } returns response(listOf(1, 2, 3), 2)
        // when
        worker.processPending()
        // then
        verify(exactly = 0) { router.dispatchConfirmed(any(), any(), any(), any()) }
    }
}
