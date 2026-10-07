package com.scoophub.system

import com.scoophub.TestcontainersConfiguration
import com.scoophub.global.auth.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

/** legacy tests/test_config_router.py + test_schedules_router.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SystemConfigAndScheduleTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val jdbcClient: JdbcClient,
) {
    private val bearer by lazy { "Bearer ${jwtService.create("admin@test.com", true)}" }

    // ── config ─────────────────────────────────────────────────────────

    @Test
    fun `전체 config 조회 — seed 6종(V26 reddit 정리)`() {
        mockMvc.get("/api/config").andExpect {
            jsonPath("$.data.length()") { value(6) }
            jsonPath("$.data[0].crawler") { exists() }
            jsonPath("$.data[0].params") { exists() }
        }
    }

    @Test
    fun `단일 config 조회와 404`() {
        mockMvc.get("/api/config/hacker_news").andExpect {
            jsonPath("$.data.crawler") { value("hacker_news") }
            jsonPath("$.data.params.max_items") { value(100) }
        }

        mockMvc.get("/api/config/nonexistent").andExpect { status { isNotFound() } }
    }

    @Test
    fun `config PATCH — 부분 병합`() {
        mockMvc.patch("/api/config/hacker_news") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"params": {"min_score": 80}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.params.min_score") { value(80) }
            jsonPath("$.data.params.max_items") { value(100) } // 기존값 유지
        }
        // 원복
        mockMvc.patch("/api/config/hacker_news") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"params": {"min_score": 50}}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `config PATCH — unknown 키는 422`() {
        mockMvc.patch("/api/config/hacker_news") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"params": {"no_such_key": 1}}"""
        }.andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `config PATCH — 비로그인 401`() {
        mockMvc.patch("/api/config/hacker_news") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"params": {"min_score": 80}}"""
        }.andExpect { status { isUnauthorized() } }
    }

    // ── schedules ──────────────────────────────────────────────────────

    @Test
    fun `전체 스케줄 조회 — seed 14종(V19 로 1행, V22 news, V23 kal_bonus, V24·V26 정리, V30 추가), 미등록 잡은 paused=null`() {
        mockMvc.get("/api/schedules").andExpect {
            jsonPath("$.data.length()") { value(11) }
            jsonPath("$.data[0].job_id") { exists() }
            jsonPath("$.data[0].schedule_type") { exists() }
            jsonPath("$.data[0].next_run_time") { doesNotExist() } // null
            jsonPath("$.data[0].paused") { doesNotExist() } // null (미등록)
        }
    }

    @Test
    fun `단일 스케줄 조회와 404`() {
        mockMvc.get("/api/schedules/weather/weather_crawler").andExpect {
            jsonPath("$.data.crawler") { value("weather") }
            jsonPath("$.data.schedule_minutes") { value(30) }
        }

        mockMvc.get("/api/schedules/nope/nope").andExpect { status { isNotFound() } }
    }

    @Test
    fun `스케줄 PATCH — cron 타입에 schedules 설정`() {
        try {
            mockMvc.patch("/api/schedules/github_trending/github_trending_crawler") {
                header("Authorization", bearer)
                contentType = MediaType.APPLICATION_JSON
                content = """{"schedules": ["30 9 * * *"]}"""
            }.andExpect {
                // 잡이 등록 안 된 테스트 환경에선 DB 갱신 후 런타임 반영에서 500 (legacy 동일)
                status { isInternalServerError() }
            }
            // DB 는 갱신됐다
            val schedules = jdbcClient.sql(
                "SELECT schedules FROM crawl_schedule WHERE crawler='github_trending' AND job_id='github_trending_crawler'",
            ).query { rs, _ -> rs.getArray("schedules") }.single()
            org.assertj.core.api.Assertions.assertThat(schedules.toString()).contains("30 9 * * *")
        } finally {
            // 원복
            mockMvc.patch("/api/schedules/github_trending/github_trending_crawler") {
                header("Authorization", bearer)
                contentType = MediaType.APPLICATION_JSON
                content = """{"schedules": ["0 9 * * *"]}"""
            }.andExpect { status { isInternalServerError() } }
        }
    }

    @Test
    fun `crawl_schedule CHECK — 비양수 interval 과 빈 cron 저장 거부`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            jdbcClient.sql(
                "UPDATE crawl_schedule SET schedule_minutes = 0 WHERE crawler='weather' AND job_id='weather_crawler'",
            ).update()
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            jdbcClient.sql(
                "UPDATE crawl_schedule SET schedules = '{}' WHERE crawler='github_trending' AND job_id='github_trending_crawler'",
            ).update()
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test
    fun `스케줄 PATCH — 검증 오류`() {
        // 잘못된 cron expr → 422
        mockMvc.patch("/api/schedules/github_trending/github_trending_crawler") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"schedules": ["not-a-cron"]}"""
        }.andExpect { status { isUnprocessableEntity() } }

        // cron 타입에 schedule_minutes → 422
        mockMvc.patch("/api/schedules/github_trending/github_trending_crawler") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"schedule_minutes": 10}"""
        }.andExpect { status { isUnprocessableEntity() } }

        // interval 타입에 schedules → 422
        mockMvc.patch("/api/schedules/weather/weather_crawler") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"schedules": ["0 9 * * *"]}"""
        }.andExpect { status { isUnprocessableEntity() } }

        // 0 이하 schedule_minutes → 422, DB 는 그대로
        mockMvc.patch("/api/schedules/weather/weather_crawler") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"schedule_minutes": 0}"""
        }.andExpect { status { isUnprocessableEntity() } }
        val minutes = jdbcClient.sql(
            "SELECT schedule_minutes FROM crawl_schedule WHERE crawler='weather' AND job_id='weather_crawler'",
        ).query(Int::class.java).single()
        org.assertj.core.api.Assertions.assertThat(minutes).isEqualTo(30)

        // 빈 body → 422
        mockMvc.patch("/api/schedules/weather/weather_crawler") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect { status { isUnprocessableEntity() } }

        // 없는 스케줄 → 404
        mockMvc.patch("/api/schedules/nope/nope") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"enabled": true}"""
        }.andExpect { status { isNotFound() } }
    }
}
