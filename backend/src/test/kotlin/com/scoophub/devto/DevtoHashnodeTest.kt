package com.scoophub.devto

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.devto.DevtoClient
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
import java.time.Instant

/** legacy tests/test_devto_hashnode.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DevtoHashnodeTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlRunner: CrawlRunner,
    private val crawler: DevtoHashnodeCrawler,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val jsonMapper = JsonMapper.builder().build()

    @MockkBean
    private lateinit var devtoClient: DevtoClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'feed' AND purpose = 'devblog'").update()
    }

    private fun articleJson(aid: Int, title: String, tags: List<String>, reactions: Int) = """
        {"id": $aid, "title": "$title", "url": "https://dev.to/a/$aid",
         "user": {"name": "Kim", "username": "kim"}, "description": "desc",
         "public_reactions_count": $reactions, "comments_count": 2, "reading_time": 5,
         "tag_list": ${tags.map { "\"$it\"" }}, "published_at": "2026-06-01T00:00:00Z"}
    """.trimIndent()

    @Test
    fun `크롤은 태그 중복 제거 후 upsert`() {
        // given — 같은 글이 두 태그에 걸침
        every { devtoClient.articlesByTag("python", 7, 30) } returns jsonMapper.readTree(
            "[" + articleJson(1, "A", listOf("python"), 10) + "]",
        )
        every { devtoClient.articlesByTag(any(), any(), any()) } returns jsonMapper.readTree("[]")
        every { devtoClient.articlesByTag("javascript", 7, 30) } returns jsonMapper.readTree(
            "[" + articleJson(1, "A", listOf("python", "javascript"), 10) + "," +
                articleJson(2, "B", listOf("javascript"), 20) +
                "]",
        )

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(2)
    }

    @Test
    fun `tag 필터와 reactions 정렬`() {
        // given
        val fetched = clock.instant().toString()
        fun row(key: String, tags: List<String>, reactions: Int) = crawlDataStore.upsert(
            "feed",
            "devblog",
            key,
            mapOf(
                "article_id" to key.toLong(),
                "title" to "t$key",
                "reactions_count" to reactions,
                "tags" to tags,
                "published_at" to fetched,
                "fetched_at" to fetched,
            ),
            clock.instant(),
        )
        row("1", listOf("python"), 10)
        row("2", listOf("javascript"), 500)
        row("3", listOf("python", "webdev"), 100)

        // when & then — 기본: reactions DESC
        mockMvc.get("/api/devto-hashnode").andExpect {
            jsonPath("$.data.length()") { value(3) }
            jsonPath("$.data[0].article_id") { value(2) }
        }

        // tag 필터 (JSONB contains)
        mockMvc.get("/api/devto-hashnode") { param("tag", "python") }.andExpect {
            jsonPath("$.data.length()") { value(2) }
        }

        mockMvc.get("/api/devto-hashnode") { param("tag", "webdev") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].article_id") { value(3) }
        }

        // 따옴표·역슬래시가 든 tag 도 JSON 캐스트 오류 없이 빈 결과
        mockMvc.get("/api/devto-hashnode") { param("tag", "a\"b\\") }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(0) }
        }
    }

    @Test
    fun `데이터 없으면 빈 목록`() {
        mockMvc.get("/api/devto-hashnode").andExpect {
            jsonPath("$.data.length()") { value(0) }
        }
    }
}
