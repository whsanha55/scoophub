package com.scoophub.system

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.global.auth.JwtService
import com.scoophub.global.notify.TelegramNotifier
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/** legacy tests/test_notify_api.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SystemNotifyApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val jdbcClient: JdbcClient,
) {
    @MockkBean
    private lateinit var telegram: TelegramNotifier

    private val bearer by lazy { "Bearer ${jwtService.create("admin@test.com", true)}" }

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM notify_log").update()
        jdbcClient.sql("DELETE FROM notify_routes").update()
        every { telegram.channel } returns "telegram"
        every { telegram.send(any(), any(), any()) } returns Unit
    }

    private fun createRoute(category: String = "news", purpose: String = "alpaca"): Long = jdbcClient.sql(
        "INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled) " +
            "VALUES (:c, :p, 'telegram', 'chat', NULL, '', true) RETURNING id",
    )
        .param("c", category)
        .param("p", purpose)
        .query { rs, _ -> rs.getLong(1) }
        .single()

    @Test
    fun `라우트 CRUD`() {
        // given — 생성
        mockMvc.post("/api/notify/routes") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"category": "weather", "chat_id": "chat9"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.category") { value("weather") }
            jsonPath("$.data.chat_id") { value("chat9") }
            jsonPath("$.data.enabled") { value(true) }
        }

        // 잘못된 채널 → 422
        mockMvc.post("/api/notify/routes") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"category": "x", "chat_id": "c", "channel": "sms"}"""
        }.andExpect { status { isUnprocessableEntity() } }

        // when & then — 수정
        val id = createRoute()
        mockMvc.patch("/api/notify/routes/$id") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"enabled": false, "topic_id": 42}"""
        }.andExpect {
            jsonPath("$.data.enabled") { value(false) }
            jsonPath("$.data.topic_id") { value(42) }
        }

        // 없는 라우트 → 404
        mockMvc.patch("/api/notify/routes/9999") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"enabled": true}"""
        }.andExpect { status { isNotFound() } }

        // 삭제
        mockMvc.delete("/api/notify/routes/$id") {
            header("Authorization", bearer)
        }.andExpect {
            jsonPath("$.data.deleted") { value(id) }
        }
        mockMvc.delete("/api/notify/routes/$id") {
            header("Authorization", bearer)
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `라우트 목록은 비로그인 허용`() {
        createRoute()
        mockMvc.get("/api/notify/routes").andExpect {
            jsonPath("$.data.length()") { value(1) }
        }
    }

    @Test
    fun `발신 테스트는 notify_log 에 기록`() {
        // given
        val id = createRoute()

        // when
        mockMvc.post("/api/notify/routes/$id/test") {
            header("Authorization", bearer)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.route_id") { value(id) }
            jsonPath("$.data.status") { value("success") }
        }

        // then — 이력 조회
        mockMvc.get("/api/notify/log") {
            header("Authorization", bearer)
        }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].category") { value("news") }
        }
    }

    @Test
    fun `발신 이력 status 필터와 422`() {
        val id = createRoute()
        mockMvc.post("/api/notify/routes/$id/test") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.data.status") { value("success") }
        }

        mockMvc.get("/api/notify/log") {
            header("Authorization", bearer)
            param("status", "success")
        }.andExpect {
            jsonPath("$.data.length()") { value(1) }
        }

        mockMvc.get("/api/notify/log") {
            header("Authorization", bearer)
            param("status", "bogus")
        }.andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `관리 mutation 은 super 전용`() {
        mockMvc.post("/api/notify/routes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"chat_id": "c"}"""
        }.andExpect { status { isUnauthorized() } }

        mockMvc.get("/api/notify/log").andExpect { status { isUnauthorized() } }
    }
}
