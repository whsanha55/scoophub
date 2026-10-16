package com.scoophub.game.domain

import java.time.Instant

enum class RoomPhase { WAITING, PLAYING, FINISHED }

enum class Side { WHITE, BLACK }

enum class SidePreference { WHITE, BLACK, RANDOM }

data class RoomPlayer(
    val guestId: String,
    val nickname: String,
    val side: Side,
    /** 마지막으로 연결·메시지가 있었던 시각. 재접속 자리 찾기와 이탈 판정에 쓴다. */
    var lastSeen: Instant,
)

data class RoomMove(val side: Side, val from: String, val to: String, val notation: String, val at: Instant)

data class RoomResult(val winner: Side?, val reason: String)

/**
 * 게임 방. 게임 종류와 규칙을 모른다 — 수의 합법성은 클라이언트가 판정하고(ADR-0001)
 * 방은 차례 순서(선 후공 교대)와 시계, 기보 기록만 관리한다.
 */
class GameRoom(
    val code: String,
    val gameId: String,
    val timeRule: TimeRule,
    private val hostPreference: SidePreference,
    val createdAt: Instant,
) {
    var phase = RoomPhase.WAITING
        private set
    private val players = mutableListOf<RoomPlayer>()
    private val moves = mutableListOf<RoomMove>()
    private var clocks: MutableMap<Side, PlayerClock> = mutableMapOf()
    private var startedAt: Instant? = null
    var lastActivityAt = createdAt
        private set
    var result: RoomResult? = null
        private set

    /** 방장(첫 입장자) 진영. 무작위면 첫 대국자 입장 시 확정한다. */
    private var hostSide: Side? = null

    val playerCount: Int get() = players.size
    val isFull: Boolean get() = players.size >= 2

    fun playerOf(guestId: String): RoomPlayer? = players.firstOrNull { it.guestId == guestId }

    fun sideOf(guestId: String): Side? = playerOf(guestId)?.side

    fun playerOfSide(side: Side): RoomPlayer? = players.firstOrNull { it.side == side }

    /** 대국자로 참가. 정원이 차 있으면 null. 같은 게스트의 재참가는 자리를 되찾는다. */
    fun join(guestId: String, nickname: String, now: Instant): Side? {
        touch(now)
        playerOf(guestId)?.let {
            it.lastSeen = now
            return it.side
        }
        if (isFull) return null
        val side = when {
            players.isEmpty() ->
                when (hostPreference) {
                    SidePreference.WHITE -> Side.WHITE
                    SidePreference.BLACK -> Side.BLACK
                    SidePreference.RANDOM -> if (Math.random() < 0.5) Side.WHITE else Side.BLACK
                }

            else -> players.first().side.opposite()
        }
        if (players.isEmpty()) hostSide = side
        players.add(RoomPlayer(guestId = guestId, nickname = nickname, side = side, lastSeen = now))
        if (players.size == 2) start(now)
        return side
    }

    private fun start(now: Instant) {
        phase = RoomPhase.PLAYING
        startedAt = now
        clocks = mutableMapOf(
            Side.WHITE to initClock(timeRule),
            Side.BLACK to initClock(timeRule),
        )
        touch(now)
    }

    /** 지금 차례인 진영. 선공(white)부터 수 개수 홀짝으로 정한다. */
    fun turn(): Side = if (moves.size % 2 == 0) Side.WHITE else Side.BLACK

    /**
     * 둔 클라이언트의 경과 시간을 해당 진영 시계에 청구한다.
     * 시간패가 나면 true 를 반환한다.
     */
    fun chargeElapsed(side: Side, elapsedMs: Long): Boolean {
        if (phase != RoomPhase.PLAYING || timeRule.untimed) return false
        val next = tickClock(clocks.getValue(side), timeRule, elapsedMs)
        clocks[side] = next
        if (next.flagged) {
            finish(RoomResult(winner = side.opposite(), reason = "시간패"))
            return true
        }
        return false
    }

    /**
     * 수를 기록한다. 서버는 규칙을 검증하지 않고(ADR-0001) 차례 순서만 지킨다.
     * notation은 둔 클라이언트가 계산해 실어 보낸 값이다.
     */
    fun addMove(guestId: String, from: String, to: String, notation: String, now: Instant): RoomMove? {
        touch(now)
        if (phase != RoomPhase.PLAYING) return null
        val side = sideOf(guestId) ?: return null
        if (side != turn()) return null
        val mover = playerOfSide(side)!!
        mover.lastSeen = now
        val move = RoomMove(side = side, from = from, to = to, notation = notation, at = now)
        moves.add(move)
        clocks[side] = clockAfterMove(clocks.getValue(side), timeRule)
        return move
    }

    fun resign(guestId: String, now: Instant): RoomResult? {
        touch(now)
        val side = sideOf(guestId) ?: return null
        if (phase != RoomPhase.PLAYING) return null
        playerOfSide(side)?.let { it.lastSeen = now }
        val result = RoomResult(winner = side.opposite(), reason = "기권")
        finish(result)
        return result
    }

    /** 시간이 elapsedMs 흘렀을 때 차례 진영 시계를 갱신한다. 시간패면 결과를 반환한다. */
    fun tickActiveClock(elapsedMs: Long): RoomResult? {
        if (phase != RoomPhase.PLAYING || timeRule.untimed) return null
        val side = turn()
        val next = tickClock(clocks.getValue(side), timeRule, elapsedMs)
        clocks[side] = next
        if (next.flagged) {
            val result = RoomResult(winner = side.opposite(), reason = "시간패")
            finish(result)
            return result
        }
        return null
    }

    /** 무제한 대국에서 상대가 5분 이상 끊겼을 때 승리 처리. */
    fun claimAbandonWin(guestId: String, now: Instant, threshold: Instant): RoomResult? {
        touch(now)
        val side = sideOf(guestId) ?: return null
        if (phase != RoomPhase.PLAYING || !timeRule.untimed) return null
        val opponent = playerOfSide(side.opposite()) ?: return null
        if (opponent.lastSeen.isBefore(threshold)) {
            val result = RoomResult(winner = side, reason = "상대 장기 이탈")
            finish(result)
            return result
        }
        return null
    }

    fun clockSnapshot(): Map<Side, PlayerClock> = clocks.toMap()

    fun movesSnapshot(): List<RoomMove> = moves.toList()

    fun playersSnapshot(): List<RoomPlayer> = players.toList()

    private fun finish(result: RoomResult) {
        this.result = result
        phase = RoomPhase.FINISHED
    }

    private fun touch(now: Instant) {
        lastActivityAt = now
    }

    /** 방 수명 정리 대상인지. [now] 기준으로 판단한다. */
    fun expired(now: Instant): Boolean {
        val waitingLimit = 30 * 60L
        val finishedLimit = 10 * 60L
        val idleLimit = 30 * 60L
        val waitingSeconds = java.time.Duration.between(createdAt, now).seconds
        val idleSeconds = java.time.Duration.between(lastActivityAt, now).seconds
        if (phase == RoomPhase.WAITING && waitingSeconds > waitingLimit) return true
        if (phase == RoomPhase.FINISHED && idleSeconds > finishedLimit) return true
        return idleSeconds > idleLimit
    }
}

fun Side.opposite(): Side = if (this == Side.WHITE) Side.BLACK else Side.WHITE
