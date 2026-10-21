package com.scoophub.game.dto

import com.scoophub.game.service.GameRoom
import com.scoophub.game.vo.TimeRule
import io.swagger.v3.oas.annotations.media.Schema

data class GuestResponse(
    @field:Schema(description = "새로 발급한 게스트 ID")
    val guestId: String,
)

data class CreateRoomResponse(
    @field:Schema(description = "초대코드 (6자리 숫자)", example = "482913")
    val code: String,
)

data class TimeRuleResponse(
    @field:Schema(description = "기본 시간(ms)", example = "600000")
    val baseMs: Long,
    @field:Schema(description = "초읽기 한 번의 시간(ms)", example = "30000")
    val byoYomiMs: Long,
    @field:Schema(description = "초읽기를 넘길 수 있는 횟수", example = "1")
    val byoYomiCount: Int,
) {
    companion object {
        fun from(rule: TimeRule) = TimeRuleResponse(
            baseMs = rule.baseMs,
            byoYomiMs = rule.byoYomiMs,
            byoYomiCount = rule.byoYomiCount,
        )
    }
}

data class RoomSummaryResponse(
    @field:Schema(description = "방이 있는지")
    val exists: Boolean,
    @field:Schema(description = "방 진행 단계 (WebSocket room 메시지와 같은 소문자)", example = "waiting")
    val phase: String?,
    @field:Schema(description = "게임 종류", example = "chess")
    val gameId: String?,
    @field:Schema(description = "시간 규칙")
    val timeRule: TimeRuleResponse?,
    @field:Schema(description = "대국자 수")
    val playerCount: Int?,
    @field:Schema(description = "대국자 자리가 다 찼는지 (찼으면 관전자로 들어간다)")
    val isFull: Boolean?,
) {
    companion object {
        /** 방이 없으면 exists=false 만 채운다. 호출하는 쪽에서 방을 잠근 채로 부른다. */
        fun from(room: GameRoom?) = RoomSummaryResponse(
            exists = room != null,
            phase = room?.phase?.name?.lowercase(),
            gameId = room?.gameId,
            timeRule = room?.let { TimeRuleResponse.from(it.timeRule) },
            playerCount = room?.playerCount,
            isFull = room?.isFull,
        )
    }
}
