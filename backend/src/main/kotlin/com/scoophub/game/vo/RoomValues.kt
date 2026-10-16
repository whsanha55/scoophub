package com.scoophub.game.vo

import com.scoophub.game.enums.RoomRoleEnum
import com.scoophub.game.enums.SideEnum
import java.time.Instant

data class ChatEntry(
    val nickname: String,
    val text: String,
    /** 대국자가 쓴 메시지인지. 시스템 메시지는 false. */
    val isFromPlayer: Boolean,
    val isSystem: Boolean = false,
    val at: Instant,
)

data class JoinResult(val side: SideEnum?, val role: RoomRoleEnum, val isAllowed: Boolean)

data class RoomMove(val side: SideEnum, val from: String, val to: String, val notation: String, val at: Instant)

data class RoomResult(val winner: SideEnum?, val reason: String)

/** 수를 둔 결과. 차례가 아니면 거절, 둘 사람 시계가 이미 다 됐으면 시간패로 끝난다. */
sealed class MoveResult {
    data object Rejected : MoveResult()

    data class TimedOut(val result: RoomResult) : MoveResult()

    data class Moved(val move: RoomMove) : MoveResult()
}
