package com.scoophub.game.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

data class CreateRoomRequest(
    @field:Schema(description = "게임 종류", example = "chess")
    @field:NotBlank
    val gameId: String,

    @field:Schema(description = "시간 규칙. 상한을 넘는 값은 서버가 맞춘다")
    @field:Valid
    val timeRule: TimeRuleRequest,

    @field:Schema(description = "방장 진영 (white, black, random)", example = "random")
    val sidePreference: String,

    @field:Schema(description = "방장 게스트 ID", example = "0b5d1c1e-8f1a-4d4e-9a55-6a1f0c7e2b11")
    @field:NotBlank
    val guestId: String,

    @field:Schema(description = "방장 닉네임", example = "게스트1234")
    @field:NotBlank
    val nickname: String,

    @field:Schema(description = "시작 전 대국자별 준비(장기 상차림)가 필요한지", example = "false")
    val isSetupRequired: Boolean = false,
) {
    fun toCommand(ip: String) = CreateRoomCommand(
        gameId = gameId,
        baseMs = timeRule.baseMs,
        byoYomiMs = timeRule.byoYomiMs,
        byoYomiCount = timeRule.byoYomiCount,
        sidePreference = sidePreference,
        guestId = guestId,
        nickname = nickname,
        isSetupRequired = isSetupRequired,
        ip = ip,
    )
}

data class TimeRuleRequest(
    @field:Schema(description = "기본 시간(ms). 0이면 첫 수부터 초읽기", example = "600000")
    val baseMs: Long,

    @field:Schema(description = "초읽기 한 번의 시간(ms)", example = "30000")
    val byoYomiMs: Long,

    @field:Schema(description = "초읽기를 넘길 수 있는 횟수", example = "1")
    val byoYomiCount: Int,
)
