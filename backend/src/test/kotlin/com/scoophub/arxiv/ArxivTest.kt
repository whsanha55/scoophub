package com.scoophub.arxiv

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.arxiv.ArxivClient
import com.scoophub.external.arxiv.ArxivPaper
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlRunner
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
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

/** legacy tests/test_arxiv.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ArxivTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlRunner: CrawlRunner,
    private val crawler: ArxivCrawler,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    @MockkBean
    private lateinit var arxivClient: ArxivClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'feed' AND purpose = 'arxiv'").update()
    }

    private fun paper(arxivId: String, title: String, category: String = "cs.AI") = ArxivPaper(
        arxivId = arxivId,
        title = title,
        authors = listOf("Author One", "Author Two"),
        summary = "summary of $title",
        primaryCategory = category,
        categories = listOf(category, "cs.LG"),
        pdfUrl = "https://arxiv.org/pdf/$arxivId",
        entryId = "https://arxiv.org/abs/$arxivId",
        published = Instant.parse("2026-06-01T00:00:00Z"),
        updated = Instant.parse("2026-06-02T00:00:00Z"),
        authorComment = null,
        journalRef = null,
    )

    @Test
    fun `크롤은 crawl_data 에 upsert`() {
        // given — crawl_config seed: categories 4종이지만 응답은 cs.AI 만
        every { arxivClient.searchByCategory(any(), any()) } returns emptyList()
        every { arxivClient.searchByCategory("cs.AI", 25) } returns listOf(paper("2401.00001", "Paper A"))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isEqualTo(1)
        val key = jdbcClient.sql(
            "SELECT key FROM crawl_data WHERE category='feed' AND purpose='arxiv'",
        ).query { rs, _ -> rs.getString("key") }.single()
        assertThat(key).isEqualTo("2401.00001")
    }

    @Test
    fun `기존 논문은 신규 산정 제외`() {
        // given
        crawlDataStore.upsert(
            "feed",
            "arxiv",
            "2401.00001",
            mapOf("title" to "old"),
            Instant.parse("2026-06-01T00:00:00Z"),
        )
        every { arxivClient.searchByCategory(any(), any()) } returns emptyList()
        every { arxivClient.searchByCategory("cs.AI", 25) } returns listOf(
            paper("2401.00001", "Paper A"),
            paper("2401.00002", "Paper B"),
        )

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(1)
    }

    @Test
    fun `category·query 필터와 정렬`() {
        // given
        val fetched = clock.instant().toString()
        fun row(key: String, title: String, category: String, published: String) = crawlDataStore.upsert(
            "feed",
            "arxiv",
            key,
            mapOf(
                "arxiv_id" to key,
                "title" to title,
                "primary_category" to category,
                "published_at" to published,
                "fetched_at" to fetched,
            ),
            Instant.parse(published),
        )
        row("2401.00001", "Transformer Survey", "cs.AI", "2026-06-01T00:00:00Z")
        row("2401.00002", "GAN Improvements", "cs.LG", "2026-06-03T00:00:00Z")

        // when & then — 기본: published(date_at) DESC
        mockMvc.get("/api/arxiv").andExpect {
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].arxiv_id") { value("2401.00002") }
        }

        // category 필터
        mockMvc.get("/api/arxiv") { param("category", "cs.AI") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].primary_category") { value("cs.AI") }
        }

        // query ILIKE (대소문자 무시)
        mockMvc.get("/api/arxiv") { param("query", "transformer") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].title") { value("Transformer Survey") }
        }
    }

    @Test
    fun `데이터 없으면 빈 목록`() {
        mockMvc.get("/api/arxiv").andExpect {
            jsonPath("$.data.length()") { value(0) }
        }
    }
}

/** Atom XML 파싱 검증 — 스프링 컨텍스트 없는 순수 단위 */
class ArxivClientParseTest {
    private val client = ArxivClient(org.springframework.web.client.RestClient.builder())

    @Test
    fun `Atom 엔트리 파싱`() {
        // given — arXiv Atom API 응답 축약본
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>http://arxiv.org/abs/2401.12345v2</id>
                <updated>2026-01-20T00:00:00Z</updated>
                <published>2026-01-15T08:00:00Z</published>
                <title>Attention Is All We   Need</title>
                <summary>We propose a new architecture.</summary>
                <author><name>Ashish Vaswani</name></author>
                <author><name>Noam Shazeer</name></author>
                <arxiv:primary_category xmlns:arxiv="http://arxiv.org/schemas/atom" term="cs.CL"/>
                <category term="cs.CL"/>
                <category term="cs.LG"/>
                <link title="pdf" href="http://arxiv.org/pdf/2401.12345v2"/>
                <arxiv:comment xmlns:arxiv="http://arxiv.org/schemas/atom">14 pages</arxiv:comment>
              </entry>
            </feed>
        """.trimIndent()

        // when
        val papers = client.parse(Jsoup.parse(xml, "", Parser.xmlParser()))

        // then
        assertThat(papers).hasSize(1)
        val p = papers.single()
        assertThat(p.arxivId).isEqualTo("2401.12345")
        assertThat(p.title).isEqualTo("Attention Is All We Need")
        assertThat(p.authors).containsExactly("Ashish Vaswani", "Noam Shazeer")
        assertThat(p.primaryCategory).isEqualTo("cs.CL")
        assertThat(p.categories).containsExactly("cs.CL", "cs.LG")
        assertThat(p.pdfUrl).isEqualTo("http://arxiv.org/pdf/2401.12345v2")
        assertThat(p.published).isEqualTo(Instant.parse("2026-01-15T08:00:00Z"))
        assertThat(p.authorComment).isEqualTo("14 pages")
    }
}
