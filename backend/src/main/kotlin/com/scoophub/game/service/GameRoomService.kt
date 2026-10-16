package com.scoophub.game.service

import com.scoophub.game.domain.ChatEntry
import com.scoophub.game.domain.GameRoom
import com.scoophub.game.domain.RoomMove
import com.scoophub.game.domain.RoomPhase
import com.scoophub.game.domain.RoomPlayer
import com.scoophub.game.domain.RoomResult
import com.scoophub.game.domain.Side
import com.scoophub.game.domain.SidePreference
import com.scoophub.game.domain.TimeRule
import com.scoophub.game.domain.opposite
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 방 조회/입장 결과 */
sealed class RoomLookup {
    data class Found(val room: GameRoom) : RoomLookup()
    data class NotFound(val reason: String) : RoomLookup()
}

/** 방에서 일어난 사건. WS 핸들러가 이벤트를 참가자에게 전파한다. */
sealed class RoomEvent {
    abstract val room: GameRoom

    data class StateChanged(override val room: GameRoom) : RoomEvent()
    data class Moved(override val room: GameRoom, val move: RoomMove) : RoomEvent()
    data class Ended(override val room: GameRoom, val result: RoomResult) : RoomEvent()
    data class Chatted(override val room: GameRoom, val entry: ChatEntry) : RoomEvent()
}

/** 남용 방지 한도. local 프로파일에서는 끈다(ADR-0002). */
interface AbuseLimiter {
    fun canCreateRoom(guestId: String, ip: String): String?

    fun onCodeAttempt(ip: String): String?

    fun onRoomClosed(room: GameRoom)
}

class NoopAbuseLimiter : AbuseLimiter {
    override fun canCreateRoom(guestId: String, ip: String): String? = null
    override fun onCodeAttempt(ip: String): String? = null
    override fun onRoomClosed(room: GameRoom) {}
}

class DefaultAbuseLimiter(private val clock: Clock) : AbuseLimiter {
    private val roomsByGuest = ConcurrentHashMap<String, MutableSet<String>>()
    private val roomsByIp = ConcurrentHashMap<String, MutableSet<String>>()
    private val codeAttempts = ConcurrentHashMap<String, MutableList<Instant>>()

    override fun canCreateRoom(guestId: String, ip: String): String? {
        if (roomsByGuest[guestId]?.size ?: 0 >= GUEST_ROOM_LIMIT) return "게스트당 방은 ${GUEST_ROOM_LIMIT}개까지 만들 수 있습니다"
        if (roomsByIp[ip]?.size ?: 0 >= IP_ROOM_LIMIT) return "같은 IP에서 방을 더 만들 수 없습니다"
        return null
    }

    override fun onCodeAttempt(ip: String): String? {
        val now = clock.instant()
        val attempts = codeAttempts.computeIfAbsent(ip) { Collections.synchronizedList(mutableListOf()) }
        synchronized(attempts) {
            attempts.removeAll { Duration.between(it, now).seconds > 60 }
            if (attempts.size >= CODE_ATTEMPT_LIMIT) return "초대코드 확인이 너무 잦습니다. 잠시 뒤 시도하세요"
            attempts.add(now)
        }
        return null
    }

    override fun onRoomClosed(room: GameRoom) {
        for (player in room.playersSnapshot()) {
            roomsByGuest[player.guestId]?.remove(room.code)
        }
        // 방 생성자 IP는 별도 기록하지 않는다: 생성 시 등록만 한다 (registerCreation)
    }

    fun registerCreation(room: GameRoom, guestId: String, ip: String) {
        roomsByGuest.computeIfAbsent(guestId) { ConcurrentHashMap.newKeySet() }.add(room.code)
        roomsByIp.computeIfAbsent(ip) { ConcurrentHashMap.newKeySet() }.add(room.code)
    }

    fun totalRooms(): Int = roomsByGuest.values.sumOf { it.size }

    companion object {
        const val GUEST_ROOM_LIMIT = 3
        const val IP_ROOM_LIMIT = 20
        const val TOTAL_ROOM_LIMIT = 200
        const val CODE_ATTEMPT_LIMIT = 10
    }
}

/**
 * 게임 방 관리. DB 없이 메모리에서만 유지한다(ADR-0002).
 * 시계 판정과 방 수명 정리는 자체 스케줄러로 돈다 — 크롤링 ENABLE_SCHEDULER 와 무관하게
 * 대국 시계는 항상 흘러야 하기 때문이다.
 */
@Service
class GameRoomService(private val clock: Clock, private val limiter: AbuseLimiter) : AutoCloseable {
    private val rooms = ConcurrentHashMap<String, GameRoom>()
    private val codeInUse = ConcurrentHashMap.newKeySet<String>()
    private val random = SecureRandom()
    private val scheduler: ScheduledExecutorService
    private val tickCounter = AtomicInteger(0)

    /** 방 이벤트 구독(WS 핸들러가 등록). 메인 테스트에서는 등록하지 않는다. */
    var eventListener: ((RoomEvent) -> Unit)? = null

    init {
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "game-room-ticker").apply { isDaemon = true }
        }
        scheduler.scheduleAtFixedRate({ runTick() }, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    fun createRoom(
        gameId: String,
        timeRule: TimeRule,
        preference: SidePreference,
        guestId: String,
        nickname: String,
        ip: String,
        requiresSetup: Boolean = false,
    ): GameRoom {
        val now = clock.instant()
        if (rooms.size >= DefaultAbuseLimiter.TOTAL_ROOM_LIMIT) {
            throw RoomLimitException("서버 방 수 한도에 걸렸습니다")
        }
        limiter.canCreateRoom(guestId, ip)?.let { throw RoomLimitException(it) }
        val room = GameRoom(
            code = newCode(),
            gameId = gameId,
            timeRule = timeRule,
            hostPreference = preference,
            requiresSetup = requiresSetup,
            createdAt = now,
        )
        rooms[room.code] = room
        codeInUse.add(room.code)
        (limiter as? DefaultAbuseLimiter)?.registerCreation(room, guestId, ip)
        room.join(guestId, nickname, now)
        return room
    }

    /** 초대코드 시도(REST 확인·입장 모두). 한도를 넘으면 예외. */
    fun checkCodeAttempt(ip: String) {
        limiter.onCodeAttempt(ip)?.let { throw RoomLimitException(it) }
    }

    fun roomOf(code: String): RoomLookup {
        val room = rooms[code] ?: return RoomLookup.NotFound("방을 찾을 수 없습니다")
        return RoomLookup.Found(room)
    }

    fun join(code: String, guestId: String, nickname: String): com.scoophub.game.domain.JoinResult {
        val room = rooms[code] ?: throw RoomNotFoundException()
        val now = clock.instant()
        val result = room.join(guestId, nickname, now)
        if (!result.allowed) return result
        room.addSystemChat("$nickname 입장", now)
        // 대국이 시작되면 시계 기준점을 잡는다.
        if (room.phase == RoomPhase.PLAYING) {
            lastClockTick.putIfAbsent(code, now)
        }
        eventListener?.invoke(RoomEvent.StateChanged(room))
        return result
    }

    fun chat(room: GameRoom, guestId: String, text: String): ChatEntry? {
        val entry = room.addChat(guestId, text, clock.instant()) ?: return null
        eventListener?.invoke(RoomEvent.Chatted(room, entry))
        return entry
    }

    /** 관전자 퇴장 처리와 알림. 대국자는 재접속을 위해 방에 남는다. */
    fun disconnect(room: GameRoom, guestId: String) {
        seen(room, guestId)
        val now = clock.instant()
        room.removeSpectator(guestId)?.let {
            room.addSystemChat("${it.nickname} 퇴장", now)
            eventListener?.invoke(RoomEvent.StateChanged(room))
        }
    }

    fun seen(room: GameRoom, guestId: String) {
        val now = clock.instant()
        room.playerOf(guestId)?.let { it.lastSeen = now }
        room.spectatorOf(guestId)?.let { it.lastSeen = now }
    }

    fun addMove(room: GameRoom, guestId: String, from: String, to: String, notation: String): RoomMove? {
        val now = clock.instant()
        // 수를 받은 시각 기준으로 둔 사람의 시계를 먼저 정산한다(ADR-0001).
        val side = room.sideOf(guestId) ?: return null
        val elapsed = Duration.between(lastClockTick.getOrDefault(room.code, now), now).toMillis()
        if (elapsed > 0 && room.chargeElapsed(side, elapsed)) {
            eventListener?.invoke(
                RoomEvent.Ended(room, room.result ?: RoomResult(side.opposite(), "시간패")),
            )
            return null
        }
        lastClockTick[room.code] = now
        val move = room.addMove(guestId, from, to, notation, now) ?: return null
        eventListener?.invoke(RoomEvent.Moved(room, move))
        return move
    }

    /** 시작 전 준비(장기 상차림) 제출. */
    fun submitSetup(room: GameRoom, guestId: String, pattern: String): Boolean {
        val accepted = room.submitSetup(guestId, pattern, clock.instant())
        if (accepted && room.phase == RoomPhase.PLAYING) {
            lastClockTick.putIfAbsent(room.code, clock.instant())
        }
        eventListener?.invoke(RoomEvent.StateChanged(room))
        return accepted
    }

    /** 재대국. 두 대국자 모두 동의하면 시작된다. 시작되면 true. */
    fun requestRematch(room: GameRoom, guestId: String): Boolean {
        val started = room.requestRematch(guestId, clock.instant())
        if (started) {
            lastClockTick[room.code] = clock.instant()
        }
        eventListener?.invoke(RoomEvent.StateChanged(room))
        return started
    }

    /** 끝난 방에서 방장이 시간 규칙을 바꾼다. */
    fun updateTimeRule(room: GameRoom, guestId: String, rule: TimeRule): Boolean {
        val changed = room.updateTimeRule(guestId, rule, clock.instant())
        if (changed) {
            eventListener?.invoke(RoomEvent.StateChanged(room))
        }
        return changed
    }

    /** 방 나가기. 대국자가 나가면 재대국을 할 수 없다. */
    fun leave(room: GameRoom, guestId: String) {
        val now = clock.instant()
        val nickname = room.removePlayer(guestId)?.nickname ?: room.removeSpectator(guestId)?.nickname
        if (nickname != null) {
            room.addSystemChat("$nickname 나감", now)
            eventListener?.invoke(RoomEvent.StateChanged(room))
        }
    }

    /** 클라이언트가 판정한 결과(외통수·빅장·200수)로 대국 종료. */
    fun finishWith(room: GameRoom, guestId: String, winner: Side?, reason: String): RoomResult? {
        val result = room.finishWith(guestId, winner, reason, clock.instant()) ?: return null
        eventListener?.invoke(RoomEvent.Ended(room, result))
        return result
    }

    fun resign(room: GameRoom, guestId: String): RoomResult? {
        val result = room.resign(guestId, clock.instant()) ?: return null
        eventListener?.invoke(RoomEvent.Ended(room, result))
        return result
    }

    /** 무제한 대국에서 상대 5분 이탈 시 승리 처리. */
    fun claimAbandonWin(room: GameRoom, guestId: String): RoomResult? {
        val now = clock.instant()
        val threshold = now.minus(Duration.ofMinutes(5))
        val result = room.claimAbandonWin(guestId, now, threshold) ?: return null
        eventListener?.invoke(RoomEvent.Ended(room, result))
        return result
    }

    fun closeRoom(code: String) {
        rooms.remove(code)?.let { room ->
            codeInUse.remove(room.code)
            limiter.onRoomClosed(room)
        }
    }

    fun activeRoomCount(): Int = rooms.size

    /** 1초 틱: 진행 중 방 시계 감산(5초마다), 방 수명 정리(30초마다). */
    private fun runTick() {
        try {
            val n = tickCounter.incrementAndGet()
            val now = clock.instant()
            if (n % 30 == 0) {
                rooms.values.removeIf { room ->
                    val expired = room.expired(now)
                    if (expired) limiter.onRoomClosed(room)
                    expired
                }
            }
            if (n % 5 != 0) return
            for (room in rooms.values) {
                if (room.phase != RoomPhase.PLAYING) continue
                // 마지막 갱신 이후 경과 시간만큼 차례 진영 시계를 깎는다.
                val elapsed = Duration.between(lastClockTick.getOrDefault(room.code, now), now).toMillis()
                if (elapsed <= 0) continue
                lastClockTick[room.code] = now
                room.tickActiveClock(elapsed)?.let { result ->
                    eventListener?.invoke(RoomEvent.Ended(room, result))
                }
                eventListener?.invoke(RoomEvent.StateChanged(room))
            }
        } catch (e: Exception) {
            // 틱 하나가 죽어도 스케줄러는 계속 돈다
        }
    }

    private val lastClockTick = ConcurrentHashMap<String, Instant>()

    private fun newCode(): String {
        while (true) {
            val code = (random.nextInt(900_000) + 100_000).toString()
            if (codeInUse.add(code)) return code
        }
    }
}

class RoomLimitException(message: String) : RuntimeException(message)

class RoomNotFoundException : RuntimeException("방을 찾을 수 없습니다")
