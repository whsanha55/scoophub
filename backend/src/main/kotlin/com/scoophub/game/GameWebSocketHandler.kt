package com.scoophub.game

import com.scoophub.game.domain.GameRoom
import com.scoophub.game.domain.RoomMove
import com.scoophub.game.domain.RoomPhase
import com.scoophub.game.domain.RoomPlayer
import com.scoophub.game.domain.RoomResult
import com.scoophub.game.domain.Side
import com.scoophub.game.service.GameRoomService
import com.scoophub.game.service.RoomEvent
import com.scoophub.game.service.RoomLookup
import com.scoophub.game.service.RoomNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.ByteBuffer
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * 게임 방 WebSocket (ADR-0003). 방마다 연결 하나, 메시지는 JSON {type, payload}.
 * REST(방 만들기·코드 확인)로 얻은 코드로 연결한다.
 */
@Component
class GameWebSocketHandler(
    private val gameRoomService: GameRoomService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : TextWebSocketHandler() {

    /** code → (guestId → session). 같은 게스트의 재접속은 세션을 덮어쓴다. */
    private val sessions = ConcurrentHashMap<String, MutableMap<String, WebSocketSession>>()
    private val sessionGuest = ConcurrentHashMap<WebSocketSession, Pair<String, String>>() // session → (code, guestId)
    private val pingScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "game-ws-ping").apply { isDaemon = true }
    }

    init {
        gameRoomService.eventListener = ::broadcastEvent
        // Cloudflare가 유휴 WebSocket 을 끊지 않도록 주기적으로 ping 을 보낸다.
        pingScheduler.scheduleAtFixedRate({ sendPings() }, 20, 20, TimeUnit.SECONDS)
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val uri = session.uri ?: return session.close(CloseStatus.BAD_DATA)
        val code = roomCode(uri) ?: return session.close(CloseStatus.BAD_DATA)
        val guestId = queryParam(uri, "guest") ?: return session.close(CloseStatus.BAD_DATA)
        val nickname = queryParam(uri, "nick")?.takeIf { it.isNotBlank() } ?: defaultNickname(guestId)

        val room = when (val lookup = gameRoomService.roomOf(code)) {
            is RoomLookup.NotFound -> return session.close(CloseStatus.NOT_ACCEPTABLE)
            is RoomLookup.Found -> lookup.room
        }
        val side = try {
            gameRoomService.join(code, guestId, nickname) ?: run {
                send(session, mapOf("type" to "error", "payload" to mapOf("message" to "대국자 자리가 이미 찼습니다")))
                return session.close(CloseStatus.NOT_ACCEPTABLE)
            }
        } catch (e: RoomNotFoundException) {
            return session.close(CloseStatus.NOT_ACCEPTABLE)
        }
        sessionGuest[session] = code to guestId
        sessions.computeIfAbsent(code) { ConcurrentHashMap() }[guestId] = session
        log.info { "game ws connected: room=$code guest=$guestId side=$side" }
        sendRoomState(room, guestId, session)
        broadcastRoom(room)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val (code, guestId) = sessionGuest[session] ?: return
        val room = (gameRoomService.roomOf(code) as? RoomLookup.Found)?.room ?: return
        gameRoomService.seen(room, guestId)
        val parsed = try {
            objectMapper.readValue(message.payload, Map::class.java)
        } catch (e: Exception) {
            return
        }

        @Suppress("UNCHECKED_CAST")
        val payload = (parsed["payload"] as? Map<String, Any?>) ?: emptyMap<String, Any?>()
        when (parsed["type"]) {
            "move" -> {
                val move = gameRoomService.addMove(
                    room = room,
                    guestId = guestId,
                    from = payload["from"]?.toString() ?: return,
                    to = payload["to"]?.toString() ?: return,
                    notation = payload["notation"]?.toString() ?: "",
                )
                if (move == null) {
                    send(session, mapOf("type" to "error", "payload" to mapOf("message" to "지금은 둘 수 없습니다")))
                }
            }

            "resign" -> gameRoomService.resign(room, guestId)

            "claim_abandon_win" -> {
                if (gameRoomService.claimAbandonWin(room, guestId) == null) {
                    send(session, mapOf("type" to "error", "payload" to mapOf("message" to "아직 승리 처리를 할 수 없습니다")))
                }
            }

            "ping" -> send(session, mapOf("type" to "pong", "payload" to emptyMap<String, Any?>()))
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val (code, guestId) = sessionGuest.remove(session) ?: return
        sessions[code]?.remove(guestId)
        (gameRoomService.roomOf(code) as? RoomLookup.Found)?.room?.let { room ->
            gameRoomService.seen(room, guestId)
        }
    }

    private fun broadcastEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.StateChanged -> broadcastRoom(event.room)

            is RoomEvent.Moved -> {
                broadcast(event.room, mapOf("type" to "move", "payload" to movePayload(event.room, event.move)))
                broadcastRoom(event.room)
            }

            is RoomEvent.Ended -> {
                broadcast(event.room, mapOf("type" to "game_end", "payload" to resultPayload(event.result)))
                broadcastRoom(event.room)
            }
        }
    }

    private fun broadcastRoom(room: GameRoom) {
        for ((guestId, session) in sessions[room.code] ?: emptyMap()) {
            if (session.isOpen) sendRoomState(room, guestId, session)
        }
    }

    private fun broadcast(room: GameRoom, message: Map<String, Any?>) {
        for ((_, session) in sessions[room.code] ?: emptyMap()) {
            if (session.isOpen) send(session, message)
        }
    }

    private fun sendRoomState(room: GameRoom, guestId: String, session: WebSocketSession) {
        val payload = roomPayload(room, room.sideOf(guestId))
        send(session, mapOf("type" to "room", "payload" to payload))
    }

    private fun roomPayload(room: GameRoom, youAre: Side?): Map<String, Any?> = mapOf(
        "phase" to when (room.phase) {
            RoomPhase.WAITING -> "waiting"
            RoomPhase.PLAYING -> "playing"
            RoomPhase.FINISHED -> "finished"
        },
        "game_id" to room.gameId,
        "time_rule" to mapOf(
            "base_ms" to room.timeRule.baseMs,
            "byo_yomi_ms" to room.timeRule.byoYomiMs,
            "byo_yomi_count" to room.timeRule.byoYomiCount,
        ),
        "players" to room.playersSnapshot().associate { player ->
            player.side.name.lowercase() to player.nickname
        },
        "you_are" to youAre?.name?.lowercase(),
        "turn" to room.turn().name.lowercase(),
        "moves" to room.movesSnapshot().map { movePayloadEntry(it) },
        "clocks" to clocksPayload(room),
        "result" to room.result?.let { resultPayload(it) },
        "server_now" to clock.instant().toEpochMilli(),
    )

    private fun clocksPayload(room: GameRoom): Map<String, Any?> = room.clockSnapshot().entries.associate { (side, c) ->
        side.name.lowercase() to mapOf(
            "base_left_ms" to c.baseLeftMs,
            "in_byo_yomi" to c.inByoYomi,
            "byo_yomi_left_ms" to c.byoYomiLeftMs,
            "byo_yomi_left" to c.byoYomiLeft,
            "flagged" to c.flagged,
        )
    }

    private fun movePayload(room: GameRoom, move: RoomMove): Map<String, Any?> = mapOf(
        "move" to movePayloadEntry(move),
        "turn" to room.turn().name.lowercase(),
        "clocks" to clocksPayload(room),
        "server_now" to clock.instant().toEpochMilli(),
    )

    private fun movePayloadEntry(move: RoomMove): Map<String, Any?> = mapOf(
        "side" to move.side.name.lowercase(),
        "from" to move.from,
        "to" to move.to,
        "notation" to move.notation,
    )

    private fun resultPayload(result: RoomResult): Map<String, Any?> = mapOf(
        "winner" to result.winner?.name?.lowercase(),
        "reason" to result.reason,
    )

    private fun send(session: WebSocketSession, message: Map<String, Any?>) {
        try {
            if (session.isOpen) session.sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
        } catch (e: Exception) {
            log.debug { "game ws send failed: ${e.message}" }
        }
    }

    private fun sendPings() {
        for (map in sessions.values) {
            for (session in map.values) {
                try {
                    if (session.isOpen) session.sendMessage(PingMessage(ByteBuffer.wrap(ByteArray(0))))
                } catch (e: Exception) {
                    // 닫힌 세션은 무시한다
                }
            }
        }
    }

    private fun roomCode(uri: URI): String? {
        val segments = uri.path.split("/").filter { it.isNotBlank() }
        return segments.lastOrNull()?.takeIf { segments.contains("game") }
    }

    private fun queryParam(uri: URI, key: String): String? = uri.query?.split("&")
        ?.map { it.split("=", limit = 2) }
        ?.firstOrNull { it.first() == key }
        ?.getOrNull(1)

    private fun defaultNickname(guestId: String): String = "게스트" + guestId.takeLast(4)
}
