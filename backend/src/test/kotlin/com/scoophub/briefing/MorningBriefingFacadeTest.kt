package com.scoophub.briefing

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.briefing.enums.BriefingSendResultEnum
import com.scoophub.briefing.facade.MorningBriefingFacade
import com.scoophub.global.notify.AutoTopicProvisioner
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.global.schedule.ScheduleResolver
import com.scoophub.global.schedule.ScheduleTrigger
import io.mockk.every
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MorningBriefingFacadeTest @Autowired constructor(
    private val facade: MorningBriefingFacade,
    private val scheduleResolver: ScheduleResolver,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    @MockkBean
    private lateinit var router: NotifyRouter

    @MockkBean
    private lateinit var provisioner: AutoTopicProvisioner

    private val now: Instant get() = clock.instant()

    @BeforeEach
    fun setUp() {
        jdbcClient.sql("DELETE FROM news_article").update()
        jdbcClient.sql(
            """
            DELETE FROM crawl_data
            WHERE (category = 'community' AND purpose IN ('hackernews', 'github'))
                OR (category = 'feed' AND purpose = 'devblog')
                OR category = 'briefing'
            """.trimIndent(),
        ).update()
        justRun { provisioner.ensureRoute(any(), any()) }
        every { router.dispatchBatch(any(), any(), any()) } returns true
    }

    @AfterEach
    fun restoreSettings() {
        jdbcClient.sql(
            "UPDATE crawl_config SET params = params || CAST(:params AS jsonb) WHERE crawler = 'morning_briefing'",
        ).param("params", """{"hn_min_score": 300}""").update()
    }

    private fun saveNews(
        id: Long,
        summaryKo: String?,
        importance: Int,
        age: Duration = Duration.ofHours(1),
        decisionReason: String? = null,
    ) {
        jdbcClient.sql(
            """
            INSERT INTO news_article (id, source, headline, url, published_at, source_updated_at, status,
                importance, summary_ko, decision_reason)
            VALUES (:id, 'benzinga', :headline, :url, :publishedAt, :publishedAt, 'skipped',
                :importance, :summaryKo, :decisionReason)
            """.trimIndent(),
        )
            .param("id", id)
            .param("headline", "headline $id")
            .param("url", "https://news.test/$id")
            .param("publishedAt", Timestamp.from(now.minus(age)))
            .param("importance", importance)
            .param("summaryKo", summaryKo)
            .param("decisionReason", decisionReason)
            .update()
    }

    private fun saveCrawlData(category: String, purpose: String, key: String, response: String) {
        jdbcClient.sql(
            """
            INSERT INTO crawl_data (category, purpose, key, date_at, response)
            VALUES (:category, :purpose, :key, :dateAt, CAST(:response AS jsonb))
            """.trimIndent(),
        )
            .param("category", category)
            .param("purpose", purpose)
            .param("key", key)
            .param("dateAt", Timestamp.from(now))
            .param("response", response)
            .update()
    }

    private fun saveHackerNews(id: Long, title: String, score: Int, fetchedAgo: Duration = Duration.ofHours(2)) =
        saveCrawlData(
            "community",
            "hackernews",
            "$id",
            """
            {"hn_id": $id, "title": "$title", "url": null, "score": $score, "item_type": "story",
             "fetched_at": "${now.minus(fetchedAgo)}"}
            """.trimIndent(),
        )

    private fun saveGithub(fullname: String, stars: Int, period: String = "daily") = saveCrawlData(
        "community",
        "github",
        "https://github.com/$fullname/$period",
        """
        {"fullname": "$fullname", "url": "https://github.com/$fullname", "description": "설명 $fullname",
         "current_period_stars": $stars, "period": "$period", "fetched_at": "${now.minus(Duration.ofHours(20))}"}
        """.trimIndent(),
    )

    private fun saveDevto(id: Long, title: String, reactions: Int) = saveCrawlData(
        "feed",
        "devblog",
        "$id",
        """
        {"article_id": $id, "title": "$title", "url": "https://dev.to/$id", "reactions_count": $reactions,
         "fetched_at": "${now.minus(Duration.ofHours(3))}"}
        """.trimIndent(),
    )

    private fun captureText(): () -> String {
        val messages = slot<List<Pair<String, NotifyMessage>>>()
        every { router.dispatchBatch("morning_briefing", "daily", capture(messages)) } returns true
        return { messages.captured.joinToString("\n\n") { it.second.text } }
    }

    @Test
    fun `지난 하루 기준을 넘은 뉴스와 기술 트렌드를 아침 브리핑 토픽으로 보낸다`() {
        // given
        saveNews(1, "엔비디아, 차세대 칩 출하 앞당겨", importance = 5)
        saveNews(2, "중요도 4 기사", importance = 4)
        saveNews(3, "하루 지난 기사", importance = 5, age = Duration.ofHours(30))
        saveNews(4, "같은 사건 기사", importance = 5, decisionReason = "duplicate")
        saveNews(5, null, importance = 5)
        saveHackerNews(10, "Show HN: SQLite for LLMs", score = 812)
        saveHackerNews(11, "Old HN", score = 900, fetchedAgo = Duration.ofHours(30))
        saveGithub("owner/repo", stars = 1240)
        saveGithub("owner/weekly", stars = 5000, period = "weekly")
        saveDevto(20, "Dev article", reactions = 87)
        val text = captureText()

        // when
        val result = facade.send()

        // then
        assertThat(result).isEqualTo(BriefingSendResultEnum.SENT)
        verify { provisioner.ensureRoute("morning_briefing", "daily") }
        assertThat(text()).contains(
            "☀️ 아침 브리핑",
            "엔비디아, 차세대 칩 출하 앞당겨",
            "headline 5", // 한국어 요약이 없으면 원문 제목
            """<a href="https://news.ycombinator.com/item?id=10">Show HN: SQLite for LLMs</a> (812점)""",
            "owner/repo</a> — 설명 owner/repo (+1,240★)",
            "Dev article</a> (반응 87)",
        )
        assertThat(text()).doesNotContain("중요도 4 기사", "하루 지난 기사", "같은 사건 기사", "Old HN", "owner/weekly")
    }

    @Test
    fun `한 번 브리핑에 나온 항목은 다음 브리핑에 넣지 않는다`() {
        // given
        saveNews(1, "첫 기사", importance = 5)
        facade.send()
        saveHackerNews(10, "New HN", score = 400)
        val text = captureText()

        // when
        val second = facade.send()
        val third = facade.send()

        // then
        assertThat(second).isEqualTo(BriefingSendResultEnum.SENT)
        assertThat(text()).contains("New HN").doesNotContain("첫 기사")
        assertThat(third).isEqualTo(BriefingSendResultEnum.EMPTY)
    }

    @Test
    fun `발송에 실패하면 나온 항목으로 기록하지 않는다`() {
        // given
        saveNews(1, "실패 뒤 다시 보낼 기사", importance = 5)
        every { router.dispatchBatch(any(), any(), any()) } returns false
        val failed = facade.send()
        val text = captureText()

        // when
        val retried = facade.send()

        // then
        assertThat(failed).isEqualTo(BriefingSendResultEnum.FAILED)
        assertThat(retried).isEqualTo(BriefingSendResultEnum.SENT)
        assertThat(text()).contains("실패 뒤 다시 보낼 기사")
    }

    @Test
    fun `기준을 넘은 항목이 하나도 없으면 보내지 않는다`() {
        // given
        saveNews(1, "중요도 3 기사", importance = 3)
        saveHackerNews(10, "Low HN", score = 120)

        // when
        val result = facade.send()

        // then
        assertThat(result).isEqualTo(BriefingSendResultEnum.EMPTY)
        verify(exactly = 0) { router.dispatchBatch(any(), any(), any()) }
        verify(exactly = 0) { provisioner.ensureRoute(any(), any()) }
    }

    @Test
    fun `설정에서 기준값을 바꾸면 다음 발송부터 반영된다`() {
        // given
        saveHackerNews(10, "Mid HN", score = 150)
        jdbcClient.sql(
            "UPDATE crawl_config SET params = params || CAST(:params AS jsonb) WHERE crawler = 'morning_briefing'",
        ).param("params", """{"hn_min_score": 100}""").update()
        val text = captureText()

        // when
        val result = facade.send()

        // then
        assertThat(result).isEqualTo(BriefingSendResultEnum.SENT)
        assertThat(text()).contains("Mid HN")
    }

    @Test
    fun `매일 07시 30분 스케줄로 켜진 채 등록된다`() {
        // when
        val resolved = scheduleResolver.resolveTrigger("morning_briefing", "morning_briefing")

        // then
        assertThat(resolved).isEqualTo(ScheduleResolver.Resolved(ScheduleTrigger.Cron(listOf("30 7 * * *")), true))
    }
}
