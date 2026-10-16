package com.scoophub.game

import com.scoophub.game.enums.SideEnum
import com.scoophub.game.service.GameRoom
import com.scoophub.game.service.GameRoomService
import com.scoophub.game.service.RoomEvent
import com.scoophub.game.vo.ChatEntry
import com.scoophub.game.vo.RoomMove
import com.scoophub.game.vo.RoomResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.net.URI
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

    /** 한 게스트의 연결. 요청·시계·ping 스레드가 함께 보내므로 전송은 decorator 로 직렬화한다. */
    private data class Connection(val code: String, val guestId: String, val out: WebSocketSession)

    /** 원본 세션 ID → 연결 */
    private val connections = ConcurrentHashMap<String, Connection>()

    /** 방 코드 → (게스트 → 연결). 같은 게스트가 다시 붙으면 새 연결로 바뀐다. */
    private val roomConnections = ConcurrentHashMap<String, ConcurrentHashMap<String, Connection>>()

    private val pingScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "game-ws-ping").apply { isDaemon = true }
    }

    init {
        // Cloudflare가 유휴 WebSocket 을 끊지 않도록 주기적으로 ping 을 보낸다.
        pingScheduler.scheduleAtFixedRate({ sendPings() }, 20, 20, TimeUnit.SECONDS)
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val uri = session.uri ?: return session.close(CloseStatus.BAD_DATA)
        val code = findRoomCode(uri) ?: return session.close(CloseStatus.BAD_DATA)
        val guestId = findQueryParam(uri, "guest") ?: return session.close(CloseStatus.BAD_DATA)
        val nickname = findQueryParam(uri, "nick")?.takeIf { it.isNotBlank() } ?: defaultNickname(guestId)
        val ip =
            resolveClientIp(
                session.handshakeHeaders.getFirst(CLIENT_IP_HEADER),
                session.remoteAddress?.address?.hostAddress,
            )
        val out = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES)

        val room = try {
            gameRoomService.findRoomToConnect(code, guestId, ip)
        } catch (e: ResponseStatusException) {
            sendError(out, e.reason ?: "지금은 들어갈 수 없습니다")
            return session.close(CloseStatus.POLICY_VIOLATION)
        } ?: return session.close(CloseStatus.NOT_ACCEPTABLE)

        val join = gameRoomService.join(room, guestId, nickname)
        if (!join.isAllowed) {
            sendError(out, "방 인원이 가득 찼습니다")
            return session.close(CloseStatus.NOT_ACCEPTABLE)
        }
        val connection = Connection(code = code, guestId = guestId, out = out)
        connections[session.id] = connection
        roomConnections.computeIfAbsent(code) { ConcurrentHashMap() }[guestId] = connection
        log.info { "game ws connected: room=$code guest=$guestId role=${join.role}" }
        sendRoomState(room, connection)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val connection = connections[session.id] ?: return
        val room = gameRoomService.findRoom(connection.code) ?: return
        val guestId = connection.guestId
        val parsed = try {
            objectMapper.readValue(message.payload, Map::class.java)
        } catch (e: Exception) {
            log.debug { "game ws bad message: room=${connection.code} guest=$guestId error=${e.message}" }
            return
        }
        val payload = parsed["payload"] as? Map<*, *> ?: emptyMap<String, Any?>()
        when (parsed["type"]) {
            "move" -> {
                val move = gameRoomService.addMove(
                    room = room,
                    guestId = guestId,
                    from = payload["from"]?.toString() ?: return,
                    to = payload["to"]?.toString() ?: return,
                    notation = payload["notation"]?.toString() ?: "",
                    matingMaterial = parseMatingMaterial(payload["mating_material"]),
                )
                if (move == null) sendError(connection.out, "지금은 둘 수 없습니다")
            }

            "resign" -> gameRoomService.resign(room, guestId)

            "finish" -> {
                val reason = payload["reason"]?.toString() ?: return
                if (gameRoomService.finishWith(room, guestId, parseSide(payload["winner"]), reason) == null) {
                    sendError(connection.out, "지금은 대국을 끝낼 수 없습니다")
                }
            }

            "rematch" -> gameRoomService.requestRematch(room, guestId)

            "setup" -> {
                val pattern = payload["pattern"]?.toString() ?: return
                if (!gameRoomService.submitSetup(room, guestId, pattern)) {
                    sendError(connection.out, "지금은 상차림을 고를 수 없습니다")
                }
            }

            "leave" -> {
                gameRoomService.leave(room, guestId)
                session.close(CloseStatus.NORMAL)
            }

            "time_rule" -> {
                val rule = payload["rule"] as? Map<*, *> ?: return
                val isChanged = gameRoomService.updateTimeRule(
                    room = room,
                    guestId = guestId,
                    baseMs = (rule["base_ms"] as? Number)?.toLong() ?: 0,
                    byoYomiMs = (rule["byo_yomi_ms"] as? Number)?.toLong() ?: 0,
                    byoYomiCount = (rule["byo_yomi_count"] as? Number)?.toInt() ?: 0,
                )
                if (!isChanged) sendError(connection.out, "지금은 시간 규칙을 바꿀 수 없습니다")
            }

            "chat" -> {
                val text = payload["text"]?.toString() ?: return
                if (gameRoomService.chat(room, guestId, text) == null) {
                    sendError(connection.out, "메시지를 보낼 수 없습니다")
                }
            }

            "claim_abandon_win" -> {
                if (gameRoomService.claimAbandonWin(room, guestId) == null) {
                    sendError(connection.out, "아직 승리 처리를 할 수 없습니다")
                }
            }

            "ping" -> send(connection.out, "pong", emptyMap<String, Any?>())
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val connection = connections.remove(session.id) ?: return
        // 같은 게스트가 이미 새 연결로 다시 붙었으면, 예전 연결이 닫힌 것은 끊김이 아니다
        val isCurrent = roomConnections[connection.code]?.remove(connection.guestId, connection) == true
        if (!isCurrent) return
        gameRoomService.findRoom(connection.code)?.let { gameRoomService.disconnect(it, connection.guestId) }
    }

    @EventListener
    fun onRoomEvent(event: RoomEvent) {
        val room = event.room
        when (event) {
            is RoomEvent.StateChanged -> Unit

            is RoomEvent.Moved -> broadcast(
                room,
                "move",
                gameRoomService.locked(room) {
                    movePayload(room, event.move)
                },
            )

            is RoomEvent.Ended -> broadcast(room, "game_end", resultPayload(event.result))

            is RoomEvent.Chatted -> broadcast(room, "chat", chatEntryPayload(event.entry))
        }
        if (event !is RoomEvent.Chatted) broadcastRoomState(room)
    }

    private fun broadcastRoomState(room: GameRoom) {
        roomConnections[room.code]?.values?.forEach { sendRoomState(room, it) }
    }

    private fun broadcast(room: GameRoom, type: String, payload: Map<String, Any?>) {
        roomConnections[room.code]?.values?.forEach { send(it.out, type, payload) }
    }

    private fun sendRoomState(room: GameRoom, connection: Connection) {
        send(connection.out, "room", gameRoomService.locked(room) { roomPayload(room, connection.guestId) })
    }

    private fun roomPayload(room: GameRoom, guestId: String): Map<String, Any?> {
        val youAre = room.findSide(guestId)
        return mapOf(
            "phase" to room.phase.name.lowercase(),
            "game_id" to room.gameId,
            "time_rule" to mapOf(
                "base_ms" to room.timeRule.baseMs,
                "byo_yomi_ms" to room.timeRule.byoYomiMs,
                "byo_yomi_count" to room.timeRule.byoYomiCount,
            ),
            "players" to room.playersSnapshot().associate { it.side.name.lowercase() to it.nickname },
            "you_are" to youAre?.name?.lowercase(),
            "role" to if (youAre != null) "player" else "spectator",
            "you_are_host" to (guestId == room.hostGuestId),
            "spectators" to room.spectatorsSnapshot().map { it.nickname },
            "chat" to room.chatSnapshot().map { chatEntryPayload(it) },
            "turn" to room.turn().name.lowercase(),
            "moves" to room.movesSnapshot().map { movePayloadEntry(it) },
            "rematch_votes" to room.rematchVotesSnapshot(),
            "setups" to room.setupSnapshot().entries.associate { (side, pattern) -> side.name.lowercase() to pattern },
            "clocks" to clocksPayload(room),
            "result" to room.result?.let { resultPayload(it) },
            "server_now" to clock.instant().toEpochMilli(),
        )
    }

    private fun clocksPayload(room: GameRoom): Map<String, Any?> = room.clockSnapshot().entries.associate { (side, c) ->
        side.name.lowercase() to mapOf(
            "base_left_ms" to c.baseLeftMs,
            "in_byo_yomi" to c.isInByoYomi,
            "byo_yomi_left_ms" to c.byoYomiLeftMs,
            "byo_yomi_left" to c.byoYomiLeft,
            "flagged" to c.isFlagged,
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

    private fun chatEntryPayload(entry: ChatEntry): Map<String, Any?> = mapOf(
        "nickname" to entry.nickname,
        "text" to entry.text,
        "from_player" to entry.isFromPlayer,
        "system" to entry.isSystem,
    )

    private fun resultPayload(result: RoomResult): Map<String, Any?> = mapOf(
        "winner" to result.winner?.name?.lowercase(),
        "reason" to result.reason,
    )

    private fun parseSide(value: Any?): SideEnum? = SideEnum.entries.firstOrNull {
        it.name.equals(value as? String, ignoreCase = true)
    }

    /** {"white": true, "black": false} 형태. 빠진 진영은 바꾸지 않는다. */
    private fun parseMatingMaterial(value: Any?): Map<SideEnum, Boolean> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        return raw.entries.mapNotNull { (side, hasMaterial) ->
            val parsedSide = parseSide(side) ?: return@mapNotNull null
            (hasMaterial as? Boolean)?.let { parsedSide to it }
        }.toMap()
    }

    private fun sendError(out: WebSocketSession, message: String) = send(out, "error", mapOf("message" to message))

    private fun send(out: WebSocketSession, type: String, payload: Map<String, Any?>) {
        sendRaw(out, TextMessage(objectMapper.writeValueAsString(mapOf("type" to type, "payload" to payload))))
    }

    private fun sendPings() {
        connections.values.forEach { sendRaw(it.out, PingMessage()) }
    }

    private fun sendRaw(out: WebSocketSession, message: WebSocketMessage<*>) {
        if (!out.isOpen) return
        try {
            out.sendMessage(message)
        } catch (e: Exception) {
            log.debug { "game ws send failed: session=${out.id} error=${e.message}" }
        }
    }

    private fun findRoomCode(uri: URI): String? {
        val segments = uri.path.split("/").filter { it.isNotBlank() }
        return segments.lastOrNull()?.takeIf { segments.contains("game") }
    }

    private fun findQueryParam(uri: URI, key: String): String? = uri.query?.split("&")
        ?.map { it.split("=", limit = 2) }
        ?.firstOrNull { it.first() == key }
        ?.getOrNull(1)

    private fun defaultNickname(guestId: String): String = "게스트" + guestId.takeLast(4)

    companion object {
        private const val SEND_TIME_LIMIT_MS = 5_000
        private const val SEND_BUFFER_LIMIT_BYTES = 512 * 1024
    }
}
