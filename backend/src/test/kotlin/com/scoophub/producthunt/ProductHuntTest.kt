package com.scoophub.producthunt

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.producthunt.ProductHuntClient
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

/** legacy tests/test_product_hunt.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ProductHuntTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlRunner: CrawlRunner,
    private val crawler: ProductHuntCrawler,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val jsonMapper = JsonMapper.builder().build()

    @MockkBean
    private lateinit var phClient: ProductHuntClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'community' AND purpose = 'producthunt'").update()
    }

    private fun graphQlResponse(vararg nodes: String): tools.jackson.databind.JsonNode = jsonMapper.valueToTree(
        mapOf("data" to mapOf("posts" to mapOf("edges" to nodes.map { mapOf("node" to jsonMapper.readTree(it)) }))),
    )

    private fun nodeJson(id: Int, name: String, votes: Int, topics: List<String> = listOf("AI")) = """
        {"id": "$id", "name": "$name", "tagline": "tag", "slug": "slug-$id",
         "url": "https://ph.com/p/$id", "website": "https://site.com",
         "votesCount": $votes, "commentsCount": 3,
         "featuredAt": "2026-06-01T00:00:00Z", "createdAt": "2026-06-01T00:00:00Z",
         "topics": {"edges": [${topics.joinToString(",") { """{"node": {"name": "$it"}}""" }}]}}
    """.trimIndent()

    @Test
    fun `크롤은 crawl_data 에 upsert`() {
        // given
        every { phClient.todayPosts(30) } returns graphQlResponse(
            nodeJson(1, "Alpha", 100),
            nodeJson(2, "Beta", 200),
        )

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(2)
        val keys = jdbcClient.sql(
            "SELECT key FROM crawl_data WHERE category='community' AND purpose='producthunt'",
        ).query { rs, _ -> rs.getString("key") }.list()
        assertThat(keys).containsExactlyInAnyOrder("1", "2")
    }

    @Test
    fun `API 실패 시 errors 로 반환`() {
        // given
        every { phClient.todayPosts(any()) } throws RuntimeException("PH down")

        // when
        val result = crawlRunner.run(crawler)

        // then — fetch 내부에서 잡아 errors 로
        assertThat(result).isNotNull
        assertThat(result!!.errors.single()).contains("PH down")
        assertThat(result.itemsFetched).isZero()
    }

    @Test
    fun `topic 필터와 votes 정렬`() {
        // given
        val fetched = clock.instant().toString()
        fun row(key: String, topics: List<String>, votes: Int) = crawlDataStore.upsert(
            "community",
            "producthunt",
            key,
            mapOf(
                "ph_id" to key,
                "name" to "n$key",
                "topics" to topics,
                "votes_count" to votes,
                "posted_at" to fetched,
                "fetched_at" to fetched,
            ),
            clock.instant(),
        )
        row("1", listOf("AI"), 100)
        row("2", listOf("DevTools"), 500)
        row("3", listOf("AI", "SaaS"), 200)

        // when & then — 기본: votes DESC
        mockMvc.get("/api/product-hunt").andExpect {
            jsonPath("$.data.length()") { value(3) }
            jsonPath("$.data[0].ph_id") { value("2") }
        }

        // topic 필터 (JSONB contains)
        mockMvc.get("/api/product-hunt") { param("topic", "AI") }.andExpect {
            jsonPath("$.data.length()") { value(2) }
        }
    }

    @Test
    fun `데이터 없으면 빈 목록`() {
        mockMvc.get("/api/product-hunt").andExpect {
            jsonPath("$.data.length()") { value(0) }
        }
    }
}
