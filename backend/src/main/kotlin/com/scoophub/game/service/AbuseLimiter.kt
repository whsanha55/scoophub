package com.scoophub.game.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 남용 방지 한도(ADR-0002). 걸리면 사유 문구를, 아니면 null 을 돌려준다. local 프로파일에서는 끈다. */
interface AbuseLimiter {
    fun findCreateBlockReason(guestId: String, ip: String, activeRoomCount: Int): String?

    /** 초대코드 시도를 기록하고, 한도를 넘었으면 사유를 돌려준다. */
    fun findCodeAttemptBlockReason(ip: String): String?

    /** [roomKey] 는 게임 종류와 초대코드를 묶은 방 저장소 키다. */
    fun onRoomCreated(roomKey: String, guestId: String, ip: String)

    fun onRoomClosed(roomKey: String)
}

class NoopAbuseLimiter : AbuseLimiter {
    override fun findCreateBlockReason(guestId: String, ip: String, activeRoomCount: Int): String? = null

    override fun findCodeAttemptBlockReason(ip: String): String? = null

    override fun onRoomCreated(roomKey: String, guestId: String, ip: String) = Unit

    override fun onRoomClosed(roomKey: String) = Unit
}

class DefaultAbuseLimiter(private val clock: Clock) : AbuseLimiter {
    private data class RoomOwner(val guestId: String, val ip: String)

    /** 열린 방 → 만든 사람. 방이 닫히면 빠지므로 게스트·IP별 방 수가 바로 돌아온다. */
    private val owners = ConcurrentHashMap<String, RoomOwner>()
    private val codeAttempts = ConcurrentHashMap<String, MutableList<Instant>>()

    override fun findCreateBlockReason(guestId: String, ip: String, activeRoomCount: Int): String? = when {
        activeRoomCount >= TOTAL_ROOM_LIMIT -> "서버 방 수 한도에 걸렸습니다"
        owners.values.count { it.guestId == guestId } >= GUEST_ROOM_LIMIT -> "게스트당 방은 ${GUEST_ROOM_LIMIT}개까지 만들 수 있습니다"
        owners.values.count { it.ip == ip } >= IP_ROOM_LIMIT -> "같은 IP에서 방을 더 만들 수 없습니다"
        else -> null
    }

    override fun findCodeAttemptBlockReason(ip: String): String? {
        val now = clock.instant()
        val attempts = codeAttempts.computeIfAbsent(ip) { mutableListOf() }
        synchronized(attempts) {
            attempts.removeAll { Duration.between(it, now) > CODE_ATTEMPT_WINDOW }
            if (attempts.size >= CODE_ATTEMPT_LIMIT) return "초대코드 확인이 너무 잦습니다. 잠시 뒤 시도하세요"
            attempts.add(now)
        }
        return null
    }

    override fun onRoomCreated(roomKey: String, guestId: String, ip: String) {
        owners[roomKey] = RoomOwner(guestId = guestId, ip = ip)
    }

    override fun onRoomClosed(roomKey: String) {
        owners.remove(roomKey)
    }

    companion object {
        const val GUEST_ROOM_LIMIT = 3
        const val IP_ROOM_LIMIT = 20
        const val TOTAL_ROOM_LIMIT = 200
        const val CODE_ATTEMPT_LIMIT = 10
        private val CODE_ATTEMPT_WINDOW = Duration.ofMinutes(1)
    }
}
