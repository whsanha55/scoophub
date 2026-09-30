package com.scoophub.system

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.llm.LlmClient
import com.scoophub.global.auth.JwtService
import com.scoophub.global.crawl.entity.CrawlLogEntity
import com.scoophub.global.crawl.enums.CrawlStatusEnum
import com.scoophub.global.crawl.repository.CrawlLogRepository
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock

/** legacy tests/test_api_system.py 포팅 */
@SpringBootTest(properties = ["scoophub.llm.model=test-model"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SystemTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val crawlLogRepository: CrawlLogRepository,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    @MockkBean
    private lateinit var llmClient: LlmClient

    private val bearer by lazy { "Bearer ${jwtService.create("admin@test.com", true)}" }

    @BeforeEach
    fun clean() {
        crawlLogRepository.deleteAllInBatch()
    }

    private fun log(crawler: String, detail: String = "") = crawlLogRepository.save(
        CrawlLogEntity(
            crawler = crawler,
            crawlerDetail = detail,
            status = CrawlStatusEnum.SUCCESS,
            itemsFetched = 10,
            itemsNew = 2,
            errorMessage = null,
            startedAt = clock.instant(),
            finishedAt = clock.instant(),
        ),
    )

    @Test
    fun `헬스 체크`() {
        mockMvc.get("/api/health").andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.status") { value("ok") }
        }
    }

    @Test
    fun `LLM 테스트 성공`() {
        every { llmClient.chat(any(), any()) } returns "응답"

        mockMvc.post("/api/llm/test") {
            header("Authorization", bearer)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"message": "hi"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.model") { value("test-model") }
            jsonPath("$.data.content") { value("응답") }
        }
    }

    @Test
    fun `LLM 테스트 실패는 success=false`() {
        every { llmClient.chat(any(), any()) } throws RuntimeException("api down")

        mockMvc.post("/api/llm/test") {
            header("Authorization", bearer)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"message": "hi"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("llm_failed") }
        }
    }

    @Test
    fun `LLM 테스트는 super 전용`() {
        mockMvc.post("/api/llm/test") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"message": "hi"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `크롤 로그 필터와 snake_case 응답`() {
        // given
        log("news", "alpaca")
        log("weather", "forecast")
        log("news", "other")

        // when & then — 전체 최신순
        mockMvc.get("/api/crawl-logs").andExpect {
            jsonPath("$.data.length()") { value(3) }
            jsonPath("$.data[0].status") { value("success") }
            jsonPath("$.data[0].crawler_detail") { exists() }
        }

        // crawler 필터
        mockMvc.get("/api/crawl-logs") { param("crawler", "news") }.andExpect {
            jsonPath("$.data.length()") { value(2) }
        }

        // crawler + detail 필터
        mockMvc.get("/api/crawl-logs") {
            param("crawler", "news")
            param("crawler_detail", "alpaca")
        }.andExpect {
            jsonPath("$.data.length()") { value(1) }
        }
    }

    @Test
    fun `빈 로그는 빈 목록`() {
        mockMvc.get("/api/crawl-logs").andExpect {
            jsonPath("$.data.length()") { value(0) }
        }
    }
}
