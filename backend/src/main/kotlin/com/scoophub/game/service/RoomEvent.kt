package com.scoophub.game.service

import com.scoophub.game.vo.ChatEntry
import com.scoophub.game.vo.RoomMove
import com.scoophub.game.vo.RoomResult

/** 방에서 일어난 사건. Spring 이벤트로 발행하고, WS 핸들러가 받아 참가자에게 전파한다. */
sealed class RoomEvent {
    abstract val room: GameRoom

    data class StateChanged(override val room: GameRoom) : RoomEvent()

    data class Moved(override val room: GameRoom, val move: RoomMove) : RoomEvent()

    data class Ended(override val room: GameRoom, val result: RoomResult) : RoomEvent()

    data class Chatted(override val room: GameRoom, val entry: ChatEntry) : RoomEvent()
}
