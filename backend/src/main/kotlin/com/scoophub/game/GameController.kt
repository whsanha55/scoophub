package com.scoophub.game

import com.scoophub.game.domain.RoomPhase
import com.scoophub.game.domain.SidePreference
import com.scoophub.game.domain.TimeRule
import com.scoophub.game.service.GameRoomService
import com.scoophub.game.service.RoomLimitException
import com.scoophub.game.service.RoomLookup
import com.scoophub.game.service.RoomNotFoundException
import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

private const val MAX_BASE_MS = 60L * 60_000
private const val MAX_BYOYOMI_MS = 60_000L
private const val MAX_BYOYOMI_COUNT = 5

data class CreateRoomRequest(
    val gameId: String,
    val timeRule: TimeRuleRequest,
    val sidePreference: String,
    val guestId: String,
    val nickname: String,
)

data class TimeRuleRequest(val baseMs: Long, val byoYomiMs: Long, val byoYomiCount: Int)

data class CreateRoomData(val code: String)

data class RoomSummaryData(
    val exists: Boolean,
    val phase: RoomPhase?,
    val gameId: String?,
    val timeRule: TimeRule?,
    val playerCount: Int?,
    val full: Boolean?,
)

data class GuestData(val guestId: String)

data class NudgeData(val ok: Boolean)

@Tag(name = "Game", description = "게임 방 API (ADR-0002, ADR-0003)")
@RestController
@RequestMapping("/api/game")
class GameController(private val gameRoomService: GameRoomService, private val clock: Clock) {
    private val random = SecureRandom()

    @Operation(summary = "게스트 ID 발급")
    @PostMapping("/guests")
    fun issueGuest(): ApiResponse<GuestData> =
        ApiResponse.ok(GuestData(guestId = UUID.randomUUID().toString()), ResponseMeta(clock.instant()))

    @Operation(summary = "방 만들기 (방장)")
    @PostMapping("/rooms")
    fun createRoom(
        @RequestBody request: CreateRoomRequest,
        servletRequest: HttpServletRequest,
    ): ApiResponse<CreateRoomData> {
        if (request.gameId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "gameId 가 비었습니다")
        }
        val rule = normalize(request.timeRule)
        val room = try {
            gameRoomService.createRoom(
                gameId = request.gameId,
                timeRule = rule,
                preference = SidePreference.entries.firstOrNull {
                    it.name.equals(request.sidePreference, ignoreCase = true)
                } ?: SidePreference.RANDOM,
                guestId = request.guestId,
                nickname = request.nickname,
                ip = clientIp(servletRequest),
            )
        } catch (e: RoomLimitException) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.message)
        }
        return ApiResponse.ok(CreateRoomData(code = room.code), ResponseMeta(clock.instant()))
    }

    @Operation(summary = "초대코드 확인")
    @GetMapping("/rooms/{code}")
    fun roomSummary(@PathVariable code: String, servletRequest: HttpServletRequest): ApiResponse<RoomSummaryData> {
        val meta = ResponseMeta(clock.instant())
        try {
            gameRoomService.checkCodeAttempt(clientIp(servletRequest))
        } catch (e: RoomLimitException) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.message)
        }
        return when (val lookup = gameRoomService.roomOf(code)) {
            is RoomLookup.NotFound ->
                ApiResponse.ok(
                    RoomSummaryData(
                        exists = false,
                        phase = null,
                        gameId = null,
                        timeRule = null,
                        playerCount = null,
                        full = null,
                    ),
                    meta,
                )

            is RoomLookup.Found -> {
                val room = lookup.room
                ApiResponse.ok(
                    RoomSummaryData(
                        exists = true,
                        phase = room.phase,
                        gameId = room.gameId,
                        timeRule = room.timeRule,
                        playerCount = room.playerCount,
                        full = room.isFull,
                    ),
                    meta,
                )
            }
        }
    }

    private fun normalize(rule: TimeRuleRequest): TimeRule {
        val base = rule.baseMs.coerceIn(0, MAX_BASE_MS)
        val byo = rule.byoYomiMs.coerceIn(0, MAX_BYOYOMI_MS)
        val count = rule.byoYomiCount.coerceIn(0, MAX_BYOYOMI_COUNT).let { if (byo == 0L) 0 else it }
        return TimeRule(baseMs = base, byoYomiMs = byo, byoYomiCount = count)
    }

    private fun clientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.split(",").first().trim()
        return request.remoteAddr
    }
}
