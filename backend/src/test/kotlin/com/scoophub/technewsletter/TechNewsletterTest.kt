package com.scoophub.technewsletter

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.rss.RssClient
import com.scoophub.external.rss.RssEntry
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
import java.time.Clock
import java.time.Instant

/** legacy tests/test_tech_newsletter.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TechNewsletterTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlRunner: CrawlRunner,
    private val crawler: TechNewsletterCrawler,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    @MockkBean
    private lateinit var rssClient: RssClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'feed' AND purpose = 'newsletter'").update()
    }

    private fun entry(link: String, title: String, published: Instant? = null) = RssEntry(
        title = title,
        link = link,
        summary = "summary",
        author = "author",
        category = "tech",
        published = published ?: Instant.parse("2026-06-01T00:00:00Z"),
    )

    @Test
    fun `크롤은 feed 별 소스명과 함께 upsert`() {
        // given — crawl_config seed 의 4개 feed 중 2개만 응답
        every { rssClient.fetch(any()) } returns emptyList()
        every { rssClient.fetch("https://tldr.tech/api/rss/tech") } returns listOf(entry("https://a/1", "A1"))
        every { rssClient.fetch("https://techcrunch.com/feed/") } returns listOf(entry("https://b/1", "B1"))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isEqualTo(2)
        val rows = jdbcClient.sql(
            "SELECT key, response ->> 'source' AS source FROM crawl_data WHERE category='feed' AND purpose='newsletter'",
        ).query { rs, _ -> rs.getString("key") to rs.getString("source") }.list()
        assertThat(rows).containsExactlyInAnyOrder(
            "https://a/1" to "TLDR Tech",
            "https://b/1" to "TechCrunch",
        )
    }

    @Test
    fun `기존 url 은 신규 산정 제외`() {
        // given
        crawlDataStore.upsert(
            "feed",
            "newsletter",
            "https://a/1",
            mapOf("title" to "old"),
            Instant.parse("2026-06-01T00:00:00Z"),
        )
        every { rssClient.fetch(any()) } returns emptyList()
        every { rssClient.fetch("https://tldr.tech/api/rss/tech") } returns listOf(
            entry("https://a/1", "A1"),
            entry("https://a/2", "A2"),
        )

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(1)
    }

    @Test
    fun `source 필터와 최신순 정렬`() {
        // given
        val fetched = clock.instant().toString()
        fun row(key: String, source: String, published: String) = crawlDataStore.upsert(
            "feed",
            "newsletter",
            key,
            mapOf("title" to "t$key", "source" to source, "published_at" to published, "fetched_at" to fetched),
            Instant.parse(published),
        )
        row("https://a/1", "TLDR Tech", "2026-06-01T00:00:00Z")
        row("https://a/2", "TechCrunch", "2026-06-03T00:00:00Z")
        row("https://a/3", "TLDR AI", "2026-06-02T00:00:00Z")

        // when & then — 기본: published(date_at) DESC
        mockMvc.get("/api/tech-newsletter").andExpect {
            jsonPath("$.data.length()") { value(3) }
            jsonPath("$.data[0].url") { value("https://a/2") }
        }

        // source 필터
        mockMvc.get("/api/tech-newsletter") { param("source", "TLDR Tech") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].source") { value("TLDR Tech") }
        }
    }

    @Test
    fun `데이터 없으면 빈 목록`() {
        mockMvc.get("/api/tech-newsletter").andExpect {
            jsonPath("$.data.length()") { value(0) }
        }
    }
}
