package com.scoophub.game.service

import com.scoophub.game.dto.CreateRoomCommand
import com.scoophub.game.enums.RoomPhaseEnum
import com.scoophub.game.enums.SideEnum
import com.scoophub.game.enums.SidePreferenceEnum
import com.scoophub.game.vo.ChatEntry
import com.scoophub.game.vo.JoinResult
import com.scoophub.game.vo.MoveResult
import com.scoophub.game.vo.RoomMove
import com.scoophub.game.vo.RoomResult
import com.scoophub.game.vo.TimeRule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/**
 * 게임 방 관리. DB 없이 메모리에서만 유지한다(ADR-0002).
 * 방 상태는 요청 스레드와 시계 스레드가 함께 바꾸므로 방 단위로 잠근다.
 * 시계 판정과 방 수명 정리는 자체 스케줄러로 돈다 — 크롤링 ENABLE_SCHEDULER 와 무관하게
 * 대국 시계는 항상 흘러야 하기 때문이다.
 */
@Service
class GameRoomService(
    private val clock: Clock,
    private val limiter: AbuseLimiter,
    private val eventPublisher: ApplicationEventPublisher,
) : AutoCloseable {
    private val rooms = ConcurrentHashMap<String, GameRoom>()
    private val random = SecureRandom()
    private val tickCounter = AtomicInteger(0)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "game-room-ticker").apply { isDaemon = true }
    }

    init {
        scheduler.scheduleAtFixedRate({ runTick() }, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    fun createRoom(command: CreateRoomCommand): GameRoom {
        limiter.findCreateBlockReason(command.guestId, command.ip, rooms.size)?.let { throw tooManyRequests(it) }
        val now = clock.instant()
        val room = registerWithNewCode { code ->
            GameRoom(
                code = code,
                gameId = command.gameId,
                timeRule = TimeRule.of(command.baseMs, command.byoYomiMs, command.byoYomiCount),
                hostPreference = SidePreferenceEnum.from(command.sidePreference),
                isSetupRequired = command.isSetupRequired,
                createdAt = now,
            )
        }
        limiter.onRoomCreated(room.code, command.guestId, command.ip)
        room.join(command.guestId, command.nickname, now)
        return room
    }

    /** REST 초대코드 확인. 시도마다 한도를 센다. */
    fun validateCodeAttempt(ip: String) {
        limiter.findCodeAttemptBlockReason(ip)?.let { throw tooManyRequests(it) }
    }

    fun findRoom(code: String): GameRoom? = rooms[code]

    /**
     * WS 연결 대상 방. 이미 방에 있는 게스트의 재접속은 한도를 세지 않고,
     * 처음 들어오는 연결은 초대코드 시도로 센다 — WS 로 코드를 대입해 보는 것을 막는다.
     */
    fun findRoomToConnect(code: String, guestId: String, ip: String): GameRoom? {
        val room = rooms[code]
        if (room != null && locked(room) { room.isParticipant(guestId) }) return room
        validateCodeAttempt(ip)
        return room
    }

    fun join(room: GameRoom, guestId: String, nickname: String): JoinResult {
        val result = locked(room) { room.join(guestId, nickname, clock.instant()) }
        if (result.isAllowed) publish(RoomEvent.StateChanged(room))
        return result
    }

    /** 연결이 끊겼다. 관전자는 퇴장하고, 대국자는 재접속을 기다린다. */
    fun disconnect(room: GameRoom, guestId: String) {
        locked(room) { room.disconnect(guestId, clock.instant()) }
        publish(RoomEvent.StateChanged(room))
    }

    /** 방 나가기. 대국자가 나가면 재대국을 할 수 없다. */
    fun leave(room: GameRoom, guestId: String) {
        locked(room) { room.leave(guestId, clock.instant()) }
        publish(RoomEvent.StateChanged(room))
    }

    fun chat(room: GameRoom, guestId: String, text: String): ChatEntry? {
        val entry = locked(room) { room.addChat(guestId, text, clock.instant()) } ?: return null
        publish(RoomEvent.Chatted(room, entry))
        return entry
    }

    fun addMove(
        room: GameRoom,
        guestId: String,
        from: String,
        to: String,
        notation: String,
        matingMaterial: Map<SideEnum, Boolean> = emptyMap(),
    ): RoomMove? = when (
        val result = locked(room) {
            room.addMove(guestId, from, to, notation, matingMaterial, clock.instant())
        }
    ) {
        MoveResult.Rejected -> null

        is MoveResult.TimedOut -> {
            publish(RoomEvent.Ended(room, result.result))
            null
        }

        is MoveResult.Moved -> {
            publish(RoomEvent.Moved(room, result.move))
            result.move
        }
    }

    /** 시작 전 준비(장기 상차림) 제출. */
    fun submitSetup(room: GameRoom, guestId: String, pattern: String): Boolean {
        val isAccepted = locked(room) { room.submitSetup(guestId, pattern, clock.instant()) }
        publish(RoomEvent.StateChanged(room))
        return isAccepted
    }

    /** 재대국. 두 대국자 모두 동의하면 시작된다. 시작되면 true. */
    fun requestRematch(room: GameRoom, guestId: String): Boolean {
        val isStarted = locked(room) { room.requestRematch(guestId, clock.instant()) }
        publish(RoomEvent.StateChanged(room))
        return isStarted
    }

    /** 끝난 방에서 방장이 시간 규칙을 바꾼다. 값은 방 만들기와 같은 상한으로 맞춘다. */
    fun updateTimeRule(room: GameRoom, guestId: String, baseMs: Long, byoYomiMs: Long, byoYomiCount: Int): Boolean {
        val rule = TimeRule.of(baseMs, byoYomiMs, byoYomiCount)
        val isChanged = locked(room) { room.updateTimeRule(guestId, rule, clock.instant()) }
        if (isChanged) publish(RoomEvent.StateChanged(room))
        return isChanged
    }

    /** 클라이언트가 판정한 결과(외통수·빅장·200수)로 대국 종료. */
    fun finishWith(room: GameRoom, guestId: String, winner: SideEnum?, reason: String): RoomResult? =
        endWith(room) { room.finishWith(guestId, winner, reason, clock.instant()) }

    fun resign(room: GameRoom, guestId: String): RoomResult? = endWith(room) { room.resign(guestId, clock.instant()) }

    /** 무제한 대국에서 상대 연결이 5분 이상 끊겼을 때 승리 처리. */
    fun claimAbandonWin(room: GameRoom, guestId: String): RoomResult? =
        endWith(room) { room.claimAbandonWin(guestId, clock.instant()) }

    fun closeRoom(code: String) {
        rooms.remove(code)?.let { limiter.onRoomClosed(it.code) }
    }

    fun activeRoomCount(): Int = rooms.size

    /** 방 상태를 읽거나 바꿀 때는 이 잠금 안에서 한다. */
    fun <T> locked(room: GameRoom, block: () -> T): T = synchronized(room) { block() }

    private fun endWith(room: GameRoom, end: () -> RoomResult?): RoomResult? {
        val result = locked(room, end) ?: return null
        publish(RoomEvent.Ended(room, result))
        return result
    }

    private fun publish(event: RoomEvent) {
        eventPublisher.publishEvent(event)
    }

    /** 1초 틱: 진행 중 방 시계 정산(5초마다), 방 수명 정리(30초마다). */
    private fun runTick() {
        try {
            val n = tickCounter.incrementAndGet()
            val now = clock.instant()
            if (n % EXPIRE_EVERY_TICKS == 0) {
                rooms.values
                    .filter { room -> locked(room) { room.isExpired(now) } }
                    .forEach { closeRoom(it.code) }
            }
            if (n % CLOCK_EVERY_TICKS != 0) return
            for (room in rooms.values) {
                val (isPlaying, result) = locked(room) {
                    val isPlaying = room.phase == RoomPhaseEnum.PLAYING
                    isPlaying to (if (isPlaying) room.settleClock(now) else null)
                }
                if (!isPlaying) continue
                result?.let { publish(RoomEvent.Ended(room, it)) }
                publish(RoomEvent.StateChanged(room))
            }
        } catch (e: Exception) {
            // 틱 하나가 실패해도 스케줄러는 계속 돌아야 한다
            log.error(e) { "게임 방 틱 실패" }
        }
    }

    /** 쓰이지 않는 6자리 코드로 방을 등록한다. 동시에 같은 코드를 잡지 않도록 putIfAbsent 로 확정한다. */
    private fun registerWithNewCode(create: (String) -> GameRoom): GameRoom {
        while (true) {
            val code = (random.nextInt(900_000) + 100_000).toString()
            val room = create(code)
            if (rooms.putIfAbsent(code, room) == null) return room
        }
    }

    private fun tooManyRequests(reason: String) = ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason)

    companion object {
        private const val CLOCK_EVERY_TICKS = 5
        private const val EXPIRE_EVERY_TICKS = 30
    }
}
