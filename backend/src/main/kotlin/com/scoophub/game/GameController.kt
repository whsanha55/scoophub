package com.scoophub.game

import com.scoophub.game.dto.CreateRoomRequest
import com.scoophub.game.dto.CreateRoomResponse
import com.scoophub.game.dto.GuestResponse
import com.scoophub.game.dto.RoomSummaryResponse
import com.scoophub.game.service.GameRoomService
import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

@Tag(name = "Game", description = "게임 방 API (ADR-0002, ADR-0003)")
@RestController
@RequestMapping("/api/game")
class GameController(private val gameRoomService: GameRoomService, private val clock: Clock) {

    @Operation(summary = "게스트 ID 발급")
    @PostMapping("/guests")
    fun createGuest(): ApiResponse<GuestResponse> =
        ApiResponse.ok(GuestResponse(guestId = UUID.randomUUID().toString()), ResponseMeta(clock.instant()))

    @Operation(summary = "방 만들기 (방장)")
    @PostMapping("/rooms")
    fun createRoom(
        @Valid @RequestBody request: CreateRoomRequest,
        servletRequest: HttpServletRequest,
    ): ApiResponse<CreateRoomResponse> {
        val room = gameRoomService.createRoom(request.toCommand(clientIpOf(servletRequest)))
        return ApiResponse.ok(CreateRoomResponse(code = room.code), ResponseMeta(clock.instant()))
    }

    @Operation(summary = "초대코드 확인. 초대코드는 게임마다 따로다")
    @GetMapping("/{gameId}/rooms/{code}")
    fun getRoomSummary(
        @PathVariable gameId: String,
        @PathVariable code: String,
        servletRequest: HttpServletRequest,
    ): ApiResponse<RoomSummaryResponse> {
        gameRoomService.validateCodeAttempt(clientIpOf(servletRequest))
        val room = gameRoomService.findRoom(gameId, code)
        val summary =
            room?.let { gameRoomService.locked(it) { RoomSummaryResponse.from(it) } } ?: RoomSummaryResponse.from(null)
        return ApiResponse.ok(summary, ResponseMeta(clock.instant()))
    }

    private fun clientIpOf(request: HttpServletRequest): String =
        resolveClientIp(request.getHeader(CLIENT_IP_HEADER), request.remoteAddr)
}
