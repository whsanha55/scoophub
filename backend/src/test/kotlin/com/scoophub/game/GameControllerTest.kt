package com.scoophub.game

import com.scoophub.TestcontainersConfiguration
import com.scoophub.game.service.GameRoomService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GameControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val gameRoomService: GameRoomService,
) {
    private fun createRoomBody(gameId: String = "janggi", ip: String) = """
        {
          "game_id": "$gameId",
          "time_rule": {"base_ms": 600000, "byo_yomi_ms": 30000, "byo_yomi_count": 1},
          "side_preference": "white",
          "guest_id": "guest-$ip",
          "nickname": "방장",
          "is_setup_required": true
        }
    """.trimIndent()

    @Test
    fun `방을 만들고 초대코드로 확인하면 방 요약이 snake_case 로 온다`() {
        // given
        val created = mockMvc.post("/api/game/rooms") {
            header(CLIENT_IP_HEADER, "10.0.0.1")
            contentType = MediaType.APPLICATION_JSON
            content = createRoomBody(ip = "10.0.0.1")
        }.andExpect { status { isOk() } }.andReturn()
        val code = objectMapper.readTree(created.response.contentAsString)["data"]["code"].asString()

        // when
        val summary = mockMvc.get("/api/game/janggi/rooms/$code") { header(CLIENT_IP_HEADER, "10.0.0.1") }

        // then
        summary.andExpect {
            status { isOk() }
            jsonPath("$.data.exists") { value(true) }
            jsonPath("$.data.phase") { value("WAITING") }
            jsonPath("$.data.game_id") { value("janggi") }
            jsonPath("$.data.is_full") { value(false) }
            jsonPath("$.data.player_count") { value(1) }
            jsonPath("$.data.time_rule.base_ms") { value(600000) }
        }
        assertThat(gameRoomService.findRoom("janggi", code)?.isSetupRequired).isTrue()
    }

    @Test
    fun `다른 게임의 초대코드로 확인하면 exists=false 다`() {
        // given
        val created = mockMvc.post("/api/game/rooms") {
            header(CLIENT_IP_HEADER, "10.0.0.5")
            contentType = MediaType.APPLICATION_JSON
            content = createRoomBody(gameId = "janggi", ip = "10.0.0.5")
        }.andExpect { status { isOk() } }.andReturn()
        val code = objectMapper.readTree(created.response.contentAsString)["data"]["code"].asString()

        // when
        val summary = mockMvc.get("/api/game/chess/rooms/$code") { header(CLIENT_IP_HEADER, "10.0.0.5") }

        // then
        summary.andExpect {
            status { isOk() }
            jsonPath("$.data.exists") { value(false) }
        }
    }

    @Test
    fun `없는 초대코드는 exists=false 다`() {
        // given
        // when
        val summary = mockMvc.get("/api/game/chess/rooms/000000") { header(CLIENT_IP_HEADER, "10.0.0.2") }

        // then
        summary.andExpect {
            status { isOk() }
            jsonPath("$.data.exists") { value(false) }
        }
    }

    @Test
    fun `게임 종류가 비면 400 이다`() {
        // given
        val body = createRoomBody(gameId = "", ip = "10.0.0.3")

        // when
        val response = mockMvc.post("/api/game/rooms") {
            header(CLIENT_IP_HEADER, "10.0.0.3")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

        // then
        response.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `초대코드 확인 한도를 넘으면 429 와 사유가 온다`() {
        // given
        repeat(10) { mockMvc.get("/api/game/chess/rooms/000000") { header(CLIENT_IP_HEADER, "10.0.0.4") } }

        // when
        val response = mockMvc.get("/api/game/chess/rooms/000000") { header(CLIENT_IP_HEADER, "10.0.0.4") }

        // then
        response.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.detail") { value("초대코드 확인이 너무 잦습니다. 잠시 뒤 시도하세요") }
        }
    }
}
