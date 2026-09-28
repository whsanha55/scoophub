package com.scoophub.hackernews

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.hackernews.HackerNewsApiClient
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlRunner
import io.mockk.every
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
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** legacy tests/test_hacker_news.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HackerNewsTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlRunner: CrawlRunner,
    private val crawler: HackerNewsCrawler,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val jsonMapper = JsonMapper.builder().build()

    @MockkBean
    private lateinit var api: HackerNewsApiClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'community' AND purpose = 'hackernews'").update()
    }

    private fun hnJson(iid: Int, title: String, score: Int, itype: String = "story"): String =
        """
        {"id": $iid, "title": "$title", "url": "https://news.ycombinator.com/item?id=$iid",
         "by": "user", "score": $score, "descendants": 0, "type": "$itype",
         "text": null, "time": 1748736000}
        """.trimIndent()

    private fun stubApi(ids: List<Int>, vararg items: String) {
        every { api.storyIds("top") } returns jsonMapper.readTree(ids.toString())
        every { api.storyIds("best") } returns jsonMapper.readTree("[]")
        items.forEach { json ->
            val id = jsonMapper.readTree(json)["id"].asInt()
            every { api.item(id.toLong()) } returns jsonMapper.readTree(json)
        }
    }

    @Test
    fun `크롤은 crawl_data 에 upsert`() {
        // given — crawl_config seed: min_score=50, story_types=[top, best]
        stubApi(listOf(1, 2), hnJson(1, "A", 100), hnJson(2, "B", 60))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(2)
        val keys = jdbcClient.sql(
            "SELECT key FROM crawl_data WHERE category='community' AND purpose='hackernews'",
        ).query { rs, _ -> rs.getString("key") }.list().toSet()
        assertThat(keys).containsExactlyInAnyOrder("1", "2")
    }

    @Test
    fun `min_score 필터와 신규 산정`() {
        // given — key 1 은 기존
        crawlDataStore.upsert(
            "community",
            "hackernews",
            "1",
            mapOf("title" to "old"),
            java.time.Instant.parse("2026-06-01T00:00:00Z"),
        )
        // 3은 min_score 미달(seed 50) → 제외, 2는 신규, 1은 기존
        stubApi(listOf(1, 2, 3), hnJson(1, "A-new", 80), hnJson(2, "B", 70), hnJson(3, "low", 10))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(1)
    }

    @Test
    fun `정렬과 필터`() {
        // given
        val fetched = clock.instant().toString()
        fun resp(score: Int, itype: String) = crawlDataStore.upsert(
            "community",
            "hackernews",
            "$score-$itype",
            mapOf(
                "hn_id" to null, "title" to "t$score", "url" to null, "by_user" to null,
                "score" to score, "descendants" to null, "item_type" to itype, "body_text" to null,
                "posted_at" to fetched, "fetched_at" to fetched,
            ),
            clock.instant(),
        )

        resp(100, "story")
        resp(500, "story")
        resp(30, "job")

        // when & then — score DESC (기본 item_type=story → job 제외)
        mockMvc.get("/api/hacker-news").andExpect {
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].score") { value(500) }
        }

        // item_type 필터 (job)
        mockMvc.get("/api/hacker-news") { param("item_type", "job") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].item_type") { value("job") }
        }

        // min_score 필터 (story 중 100 이상 = 2건)
        mockMvc.get("/api/hacker-news") { param("min_score", "100") }.andExpect {
            jsonPath("$.data.length()") { value(2) }
        }
    }

    @Test
    fun `데이터 없으면 빈 목록`() {
        mockMvc.get("/api/hacker-news").andExpect {
            jsonPath("$.data.length()") { value(0) }
            jsonPath("$.meta.total") { value(0) }
        }
    }
}
