package com.scoophub.game.domain

import java.time.Instant

enum class RoomPhase { WAITING, SETUP, PLAYING, FINISHED }

enum class Side { WHITE, BLACK }

enum class SidePreference { WHITE, BLACK, RANDOM }

data class RoomPlayer(
    val guestId: String,
    val nickname: String,
    val side: Side,
    /** 마지막으로 연결·메시지가 있었던 시각. 재접속 자리 찾기와 이탈 판정에 쓴다. */
    var lastSeen: Instant,
)

data class RoomSpectator(val guestId: String, val nickname: String, var lastSeen: Instant)

data class ChatEntry(
    val nickname: String,
    val text: String,
    /** 대국자면 true. 시스템 메시지면 false. */
    val fromPlayer: Boolean,
    val system: Boolean = false,
    val at: Instant,
)

enum class Role { PLAYER, SPECTATOR }

data class JoinResult(val side: Side?, val role: Role, val allowed: Boolean)

data class RoomMove(val side: Side, val from: String, val to: String, val notation: String, val at: Instant)

data class RoomResult(val winner: Side?, val reason: String)

/**
 * 게임 방. 게임 종류와 규칙을 모른다 — 수의 합법성은 클라이언트가 판정하고(ADR-0001)
 * 방은 차례 순서(선 후공 교대)와 시계, 기보 기록만 관리한다.
 */
class GameRoom(
    val code: String,
    val gameId: String,
    /** 재대국 직전에 방장이 시간 규칙을 바꿀 수 있다. */
    var timeRule: TimeRule,
    private val hostPreference: SidePreference,
    /** 시작 전 대국자별 준비(장기 상차림 등)가 필요한 방인지. */
    val requiresSetup: Boolean,
    val createdAt: Instant,
) {
    /** 방장 게스트. 시간 규칙 변경 권한이 있다. */
    var hostGuestId: String? = null
        private set

    private val rematchVotes = mutableSetOf<String>()
    var phase = RoomPhase.WAITING
        private set
    private val players = mutableListOf<RoomPlayer>()
    private val spectators = mutableListOf<RoomSpectator>()
    private val moves = mutableListOf<RoomMove>()
    private val chatLog = ArrayDeque<ChatEntry>()
    private val lastChatAt = mutableMapOf<String, Instant>()
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

    fun spectatorOf(guestId: String): RoomSpectator? = spectators.firstOrNull { it.guestId == guestId }

    /**
     * 참가. 대국자 자리가 남아 있으면 대국자, 다 찼으면 관전자로 배정한다.
     * 관전자 정원(10)까지 차면 거절한다. 같은 게스트의 재참가는 자리를 되찾는다.
     */
    fun join(guestId: String, nickname: String, now: Instant): JoinResult {
        touch(now)
        playerOf(guestId)?.let {
            it.lastSeen = now
            return JoinResult(side = it.side, role = Role.PLAYER, allowed = true)
        }
        spectatorOf(guestId)?.let {
            it.lastSeen = now
            return JoinResult(side = null, role = Role.SPECTATOR, allowed = true)
        }
        if (isFull) {
            if (spectators.size >= SPECTATOR_LIMIT) {
                return JoinResult(side = null, role = Role.SPECTATOR, allowed = false)
            }
            spectators.add(RoomSpectator(guestId = guestId, nickname = nickname, lastSeen = now))
            return JoinResult(side = null, role = Role.SPECTATOR, allowed = true)
        }
        val side = when {
            players.isEmpty() ->
                when (hostPreference) {
                    SidePreference.WHITE -> Side.WHITE
                    SidePreference.BLACK -> Side.BLACK
                    SidePreference.RANDOM -> if (Math.random() < 0.5) Side.WHITE else Side.BLACK
                }

            else -> players.first().side.opposite()
        }
        if (players.isEmpty()) {
            hostSide = side
            hostGuestId = guestId
        }
        players.add(RoomPlayer(guestId = guestId, nickname = nickname, side = side, lastSeen = now))
        if (players.size == 2 && requiresSetup) {
            phase = RoomPhase.SETUP
        } else if (players.size == 2) {
            start(now)
        }
        return JoinResult(side = side, role = Role.PLAYER, allowed = true)
    }

    /** 관전자 퇴장. 대국자는 재접속할 수 있으므로 방에 남긴다. */
    fun removeSpectator(guestId: String): RoomSpectator? {
        val spectator = spectatorOf(guestId) ?: return null
        spectators.remove(spectator)
        return spectator
    }

    /**
     * 채팅. 대국자와 관전자 모두 쓸 수 있고 1인당 1초에 1개까지.
     * 메시지는 200자까지. 위반하면 null.
     */
    fun addChat(guestId: String, text: String, now: Instant): ChatEntry? {
        touch(now)
        val trimmed = text.trim().take(MAX_CHAT_LENGTH)
        if (trimmed.isEmpty()) return null
        val isPlayer = playerOf(guestId) != null
        if (!isPlayer && spectatorOf(guestId) == null) return null
        val last = lastChatAt[guestId]
        if (last != null && java.time.Duration.between(last, now).toMillis() < 1_000) return null
        lastChatAt[guestId] = now
        val nickname = playerOf(guestId)?.nickname ?: spectatorOf(guestId)?.nickname ?: return null
        val entry = ChatEntry(
            nickname = nickname,
            text = trimmed,
            fromPlayer = isPlayer,
            at = now,
        )
        appendChat(entry)
        return entry
    }

    /** 입·퇴장 알림 등 시스템 메시지. 한도 대상 아니다. */
    fun addSystemChat(text: String, now: Instant): ChatEntry {
        val entry = ChatEntry(nickname = "", text = text, fromPlayer = false, system = true, at = now)
        appendChat(entry)
        return entry
    }

    private fun appendChat(entry: ChatEntry) {
        chatLog.addLast(entry)
        while (chatLog.size > CHAT_HISTORY) chatLog.removeFirst()
    }

    /** 시작 전 준비(장기 상차림). 두 대국자 모두 제출하면 대국이 시작된다. */
    private val setups = mutableMapOf<Side, String>()

    fun submitSetup(guestId: String, pattern: String, now: Instant): Boolean {
        touch(now)
        if (phase != RoomPhase.SETUP) return false
        val side = sideOf(guestId) ?: return false
        setups[side] = pattern
        playerOf(guestId)?.let { it.lastSeen = now }
        if (setups.size == 2) start(now)
        return true
    }

    fun setupSnapshot(): Map<Side, String> = setups.toMap()

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

/** 규칙 판정 결과(외통수·빅장 등)로 대국을 끝낸다. 판정은 클라이언트가 했다(ADR-0001). */
    fun finishWith(guestId: String, winner: Side?, reason: String, now: Instant): RoomResult? {
        touch(now)
        sideOf(guestId) ?: return null
        if (phase != RoomPhase.PLAYING) return null
        playerOf(guestId)?.let { it.lastSeen = now }
        val result = RoomResult(winner = winner, reason = reason)
        finish(result)
        return result
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

    fun spectatorsSnapshot(): List<RoomSpectator> = spectators.toList()

    fun chatSnapshot(): List<ChatEntry> = chatLog.toList()

    companion object {
        const val SPECTATOR_LIMIT = 10
        const val CHAT_HISTORY = 50
        const val MAX_CHAT_LENGTH = 200
    }

    private fun finish(result: RoomResult) {
        this.result = result
        phase = RoomPhase.FINISHED
    }

    /**
     * 재대국 투표. 끝난 방에서 양쪽 대국자가 모두 눌러야 새 대국이 시작된다.
     * 대국자가 한 명이라도 방을 나가면 더 이상 할 수 없다.
     */
    fun requestRematch(guestId: String, now: Instant): Boolean {
        touch(now)
        if (phase != RoomPhase.FINISHED) return false
        val player = playerOf(guestId) ?: return false
        if (players.size < 2) return false
        rematchVotes.add(guestId)
        player.lastSeen = now
        if (rematchVotes.size < 2 || rematchVotes != players.map { it.guestId }.toSet()) {
            return false
        }
        startRematch(now)
        return true
    }

    private fun startRematch(now: Instant) {
        // 진영 교대: 백과 흑이 서로 바뀐다
        for (i in players.indices) {
            players[i] = players[i].copy(side = players[i].side.opposite())
        }
        if (requiresSetup) {
            // 상차림을 다시 고른다
            setups.clear()
            moves.clear()
            rematchVotes.clear()
            result = null
            phase = RoomPhase.SETUP
            touch(now)
            return
        }
        moves.clear()
        rematchVotes.clear()
        result = null
        phase = RoomPhase.PLAYING
        clocks = mutableMapOf(
            Side.WHITE to initClock(timeRule),
            Side.BLACK to initClock(timeRule),
        )
        startedAt = now
        touch(now) // 방 수명 타이머를 처음부터 다시 잰다
    }

    /** 재대국 투표 현황(닉네임). */
    fun rematchVotesSnapshot(): List<String> = rematchVotes.map { v -> playerOf(v)?.nickname ?: return@map v }

    /** 대국자가 방을 나간다. 나가면 재대국을 할 수 없다. */
    fun removePlayer(guestId: String): RoomPlayer? {
        rematchVotes.remove(guestId)
        val player = playerOf(guestId) ?: return null
        players.remove(player)
        return player
    }

    /** 끝난 방에서 방장이 시간 규칙을 바꾼다(재대국 시작 전까지). */
    fun updateTimeRule(guestId: String, rule: TimeRule, now: Instant): Boolean {
        touch(now)
        if (phase != RoomPhase.FINISHED || hostGuestId != guestId) return false
        timeRule = rule
        return true
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
