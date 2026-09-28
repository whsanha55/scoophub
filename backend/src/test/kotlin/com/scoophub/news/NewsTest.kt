package com.scoophub.news

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.llm.LlmClient
import com.scoophub.external.rss.RssClient
import com.scoophub.external.rss.RssEntry
import com.scoophub.global.auth.JwtService
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Instant

/** legacy tests/test_news_crawler.py + test_dedup.py + test_summarizer.py + test_api_news.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class NewsTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val crawler: NewsCrawler,
    private val dedup: NewsDedup,
    private val summarizer: NewsSummarizer,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    @MockkBean
    private lateinit var llm: LlmClient

    @MockkBean
    private lateinit var rss: RssClient

    private val bearer by lazy { "Bearer ${jwtService.create("admin@test.com", true)}" }

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM feed_news").update()
        jdbcClient.sql("UPDATE crawl_sources SET active = true WHERE crawler = 'news'").update()
    }

    private fun entry(link: String, title: String, published: Instant? = null) = // 발행일 없음 → cutoff 통과
        RssEntry(title = title, link = link, summary = "body", author = null, category = null, published = published)

    private fun stubRss(vararg urls: Pair<String, List<RssEntry>>) {
        every { rss.fetch(any()) } returns emptyList()
        urls.forEach { (url, entries) -> every { rss.fetch(url) } returns entries }
    }

    private fun googleUrl() = jdbcClient.sql(
        "SELECT url FROM crawl_sources WHERE crawler='news' ORDER BY id LIMIT 1",
    ).query { rs, _ -> rs.getString(1) }.single()

    @Test
    fun `크롤은 normalized_url dedup 로 삽입`() {
        // given — 트래킹 파라미터만 다른 같은 기사 (Google KR 1개 소스만 활성)
        jdbcClient.sql("UPDATE crawl_sources SET active = false WHERE crawler='news' AND url <> :url")
            .param("url", googleUrl()).update()
        stubRss(
            googleUrl() to listOf(
                entry("https://news.example.com/a?utm_source=x&id=1", "기사1"),
                entry("https://news.example.com/a?id=1&fbclid=zzz", "기사1-중복"),
                entry("https://news.example.com/b", "기사2"),
            ),
        )

        // when
        val result = crawler.fetch()

        // then
        assertThat(result.itemsFetched).isEqualTo(3)
        assertThat(result.itemsNew).isEqualTo(2) // normalized_url 충돌 1건
        val count = jdbcClient.sql("SELECT COUNT(*) FROM feed_news").query { rs, _ -> rs.getInt(1) }.single()
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `URL 정규화 — 트래킹 제거·정렬·트레일링 슬래시`() {
        assertThat(NewsUrlNormalizer.normalize("https://Example.com/path/?b=2&utm_source=x&a=1"))
            .isEqualTo("https://example.com/path?a=1&b=2")
        assertThat(NewsUrlNormalizer.normalize("https://example.com/")).isEqualTo("https://example.com/")
        assertThat(NewsUrlNormalizer.normalize(null)).isEmpty()
    }

    @Test
    fun `LLM dedup — 그룹 판단 후 중복 마킹`() {
        // given
        insertArticle("기존 기사", "https://ex.com/e1")
        val newId = insertArticle("신규 중복", "https://ex.com/n1")
        every { llm.chat(any(), any()) } returns """{"groups": [["E1", "N1"]]}"""

        // when
        val deduped = dedup.llmDedup(listOf(newId))

        // then — 신규가 기존 것으로 대표 지정되어 중복 마킹
        assertThat(deduped).isEqualTo(1)
        val row = jdbcClient.sql("SELECT duplicated, duplicated_news_id FROM feed_news WHERE id = :id")
            .param("id", newId)
            .query { rs, _ -> rs.getBoolean("duplicated") to rs.getInt("duplicated_news_id") }
            .single()
        assertThat(row.first).isTrue()
    }

    @Test
    fun `LLM dedup 실패는 non-duplicate 유지`() {
        val newId = insertArticle("신규", "https://ex.com/n2")
        every { llm.chat(any(), any()) } throws RuntimeException("llm down")

        assertThat(dedup.llmDedup(listOf(newId))).isEqualTo(0)
    }

    @Test
    fun `요약 — 청크 처리와 실패 마킹`() {
        // given
        val id1 = insertArticle("속보: 시장 급등", "https://ex.com/s1") // 한국어 제목
        val id2 = insertArticle("Market crashes", "https://ex.com/s2") // 번역 필요
        every {
            llm.chat(any(), any())
        } returns """[
            {"idx": 1, "summary_ko": "시장이 급등했습니다.", "importance": 4, "category": "markets"},
            {"idx": 2, "summary_ko": "시장이 폭락했습니다.", "importance": 5, "category": "markets", "title_ko": "시장 폭락"}
        ]"""

        // when
        val result = summarizer.summarizeIncomplete()

        // then
        assertThat(result.success).isEqualTo(2)
        assertThat(result.total).isEqualTo(2)
        val row1 = jdbcClient.sql("SELECT summary, importance, category, summary_status FROM feed_news WHERE id = :id")
            .param("id", id1).query { rs, _ ->
                listOf(
                    rs.getString("summary"),
                    rs.getInt("importance"),
                    rs.getString("category"),
                    rs.getString("summary_status"),
                )
            }.single()
        assertThat(row1).containsExactly("시장이 급등했습니다.", 4, "markets", "success")
        val title2 = jdbcClient.sql("SELECT title FROM feed_news WHERE id = :id").param("id", id2)
            .query { rs, _ -> rs.getString("title") }.single()
        assertThat(title2).isEqualTo("시장 폭락") // title_ko 반영
    }

    @Test
    fun `요약 실패는 error 마킹`() {
        // given
        insertArticle("기사", "https://ex.com/f1")
        every { llm.chat(any(), any()) } throws RuntimeException("llm down")

        // when
        val result = summarizer.summarizeIncomplete()

        // then
        assertThat(result.error).isEqualTo(1)
        val status = jdbcClient.sql("SELECT summary_status FROM feed_news")
            .query { rs, _ -> rs.getString(1) }.single()
        assertThat(status).isEqualTo("error")
    }

    @Test
    fun `GET news 목록 필터`() {
        // given
        val id = insertArticle("중요 기사", "https://ex.com/api1", importance = 5, category = "markets")
        insertArticle("가벼운 기사", "https://ex.com/api2", importance = 1, category = "tech")

        // when & then
        mockMvc.get("/api/news").andExpect {
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.meta.total") { value(2) }
        }
        mockMvc.get("/api/news") { param("min_importance", "5") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].importance") { value(5) }
        }
        mockMvc.get("/api/news") { param("category", "tech") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].category") { value("tech") }
        }
        mockMvc.get("/api/news/$id").andExpect {
            jsonPath("$.data.title") { value("중요 기사") }
        }
        mockMvc.get("/api/news/9999").andExpect { status { isNotFound() } }
    }

    @Test
    fun `소스 CRUD`() {
        // given — 추가
        mockMvc.post("/api/news/sources") {
            header("Authorization", bearer)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"name": "테스트소스", "url": "https://example.com/rss"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.name") { value("테스트소스") }
            jsonPath("$.data.active") { value(true) }
        }

        // 중복 URL → 409
        mockMvc.post("/api/news/sources") {
            header("Authorization", bearer)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"name": "중복", "url": "https://example.com/rss"}"""
        }.andExpect { status { isConflict() } }

        // 수정
        val id = jdbcClient.sql("SELECT id FROM crawl_sources WHERE url = 'https://example.com/rss'")
            .query { rs, _ -> rs.getInt(1) }.single()
        mockMvc.patch("/api/news/sources/$id") {
            header("Authorization", bearer)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"active": false}"""
        }.andExpect {
            jsonPath("$.data.active") { value(false) }
        }

        // 삭제
        mockMvc.delete("/api/news/sources/$id") {
            header("Authorization", bearer)
        }.andExpect {
            jsonPath("$.data.deleted") { value(true) }
        }
    }

    @Test
    fun `크롤 트리거는 super 전용`() {
        mockMvc.post("/api/crawling/news").andExpect { status { isUnauthorized() } }
    }

    private fun insertArticle(title: String, url: String, importance: Int = 2, category: String? = null): Int =
        jdbcClient.sql(
            "INSERT INTO feed_news (source, title, url, normalized_url, importance, category) " +
                "VALUES ('test', :title, :url, :nurl, :importance, :category) RETURNING id",
        )
            .param("title", title)
            .param("url", url)
            .param("nurl", NewsUrlNormalizer.normalize(url))
            .param("importance", importance)
            .param("category", category)
            .query { rs, _ -> rs.getInt(1) }
            .single()
}
