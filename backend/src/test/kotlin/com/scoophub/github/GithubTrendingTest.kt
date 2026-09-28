package com.scoophub.github

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.github.GithubTrendingClient
import com.scoophub.external.github.TrendingRepo
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlRunner
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
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

/** legacy tests/test_github_trending.py 포팅 + Jsoup 파싱 검증 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GithubTrendingTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlRunner: CrawlRunner,
    private val crawler: GithubTrendingCrawler,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    @MockkBean
    private lateinit var trendingClient: GithubTrendingClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'community' AND purpose = 'github'").update()
    }

    private fun repo(fullname: String, stars: Int, periodStars: Int, language: String? = "Kotlin") = TrendingRepo(
        fullname = fullname,
        author = fullname.substringBefore("/"),
        name = fullname.substringAfter("/"),
        url = "https://github.com/$fullname",
        description = "desc",
        language = language,
        stars = stars,
        forks = 10,
        currentPeriodStars = periodStars,
    )

    @Test
    fun `크롤은 crawl_data 에 upsert`() {
        // given — crawl_config seed: since=daily, max_repos=25
        every { trendingClient.trending(null, "daily") } returns listOf(repo("a/one", 100, 50), repo("b/two", 200, 30))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(2)
        val keys = jdbcClient.sql(
            "SELECT key FROM crawl_data WHERE category='community' AND purpose='github'",
        ).query { rs, _ -> rs.getString("key") }.list()
        assertThat(keys).containsExactlyInAnyOrder("https://github.com/a/one", "https://github.com/b/two")
    }

    @Test
    fun `기존 url 은 신규 산정 제외`() {
        // given
        crawlDataStore.upsert(
            "community",
            "github",
            "https://github.com/a/one",
            mapOf("fullname" to "a/one"),
            java.time.Instant.parse("2026-06-01T00:00:00Z"),
        )
        every { trendingClient.trending(null, "daily") } returns listOf(repo("a/one", 100, 50), repo("b/two", 200, 30))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.itemsNew).isEqualTo(1)
    }

    @Test
    fun `period·language 필터와 정렬`() {
        // given
        val fetched = clock.instant().toString()
        fun row(key: String, period: String, language: String?, periodStars: Int) = crawlDataStore.upsert(
            "community",
            "github",
            key,
            mapOf(
                "fullname" to key,
                "period" to period,
                "language" to language,
                "current_period_stars" to periodStars,
                "stars" to 100,
                "fetched_at" to fetched,
            ),
            clock.instant(),
        )
        row("https://github.com/a/one", "daily", "Kotlin", 50)
        row("https://github.com/b/two", "daily", "Python", 300)
        row("https://github.com/c/three", "weekly", "Kotlin", 999)

        // when & then — daily 기본: weekly 제외, current_period_stars DESC
        mockMvc.get("/api/github-trending").andExpect {
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].current_period_stars") { value(300) }
        }

        // language 필터
        mockMvc.get("/api/github-trending") { param("language", "Kotlin") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].language") { value("Kotlin") }
        }

        // period 필터
        mockMvc.get("/api/github-trending") { param("period", "weekly") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].period") { value("weekly") }
        }
    }

    @Test
    fun `데이터 없으면 빈 목록`() {
        mockMvc.get("/api/github-trending").andExpect {
            jsonPath("$.data.length()") { value(0) }
        }
    }
}
