package com.scoophub.youtube

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.youtube.YoutubeClient
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

/** legacy tests/test_youtube_trending.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class YoutubeTrendingTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlRunner: CrawlRunner,
    private val crawler: YoutubeTrendingCrawler,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val jsonMapper = JsonMapper.builder().build()

    @MockkBean
    private lateinit var youtubeClient: YoutubeClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'feed' AND purpose = 'youtube'").update()
    }

    private fun chartResponse(vararg videoIds: String) = jsonMapper.readTree(
        """{"items": [${videoIds.joinToString(",") { id ->
            """{"id": "$id", "snippet": {"title": "t$id", "channelTitle": "ch", "channelId": "c1",
                "description": "d", "categoryId": "10", "publishedAt": "2026-06-01T00:00:00Z",
                "thumbnails": {"high": {"url": "https://img/$id"}}},
               "statistics": {"viewCount": "${id.hashCode().toLong() and 0xfffff}", "likeCount": "5", "commentCount": "2"},
               "contentDetails": {"duration": "PT10M"}}"""
        }}]}""",
    )

    @Test
    fun `크롤은 지역별 키로 upsert`() {
        // given — crawl_config seed: regions [KR, US]
        every { youtubeClient.mostPopular("KR", 50) } returns chartResponse("krv1", "krv2")
        every { youtubeClient.mostPopular("US", 50) } returns chartResponse("usv1")

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isEqualTo(3)
        val keys = jdbcClient.sql(
            "SELECT key FROM crawl_data WHERE category='feed' AND purpose='youtube'",
        ).query { rs, _ -> rs.getString("key") }.list()
        assertThat(keys).containsExactlyInAnyOrder("KR:krv1", "KR:krv2", "US:usv1")
    }

    @Test
    fun `region_code 필터와 view_count 정렬`() {
        // given
        val fetched = clock.instant().toString()
        fun row(key: String, region: String, views: Long) = crawlDataStore.upsert(
            "feed",
            "youtube",
            key,
            mapOf(
                "video_id" to key.substringAfter(':'),
                "title" to "t$key",
                "region_code" to region,
                "view_count" to views,
                "published_at" to fetched,
                "fetched_at" to fetched,
            ),
            clock.instant(),
        )
        row("KR:1", "KR", 100L)
        row("KR:2", "KR", 5_000_000_000L) // int 초과 — bigint 정렬
        row("US:3", "US", 999L)

        // when & then — KR 기본: bigint 내림차순
        mockMvc.get("/api/youtube-trending").andExpect {
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].video_id") { value("2") }
            jsonPath("$.data[0].view_count") { value(5000000000) }
        }

        // region_code 변경
        mockMvc.get("/api/youtube-trending") { param("region_code", "US") }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].region_code") { value("US") }
        }
    }

    @Test
    fun `데이터 없으면 빈 목록`() {
        mockMvc.get("/api/youtube-trending").andExpect {
            jsonPath("$.data.length()") { value(0) }
        }
    }
}
