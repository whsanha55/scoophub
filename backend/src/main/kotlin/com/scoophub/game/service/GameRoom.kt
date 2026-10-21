package com.scoophub.game.service

import com.scoophub.game.enums.GameEndReasonEnum
import com.scoophub.game.enums.RoomPhaseEnum
import com.scoophub.game.enums.RoomRoleEnum
import com.scoophub.game.enums.SideEnum
import com.scoophub.game.enums.SidePreferenceEnum
import com.scoophub.game.vo.ChatEntry
import com.scoophub.game.vo.JoinResult
import com.scoophub.game.vo.MoveResult
import com.scoophub.game.vo.PlayerClock
import com.scoophub.game.vo.RoomAction
import com.scoophub.game.vo.RoomMove
import com.scoophub.game.vo.RoomResult
import com.scoophub.game.vo.TimeRule
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** 대국자. 연결이 끊겨도 재접속할 수 있도록 방에 남는다. */
class RoomPlayer(val guestId: String, val nickname: String, var side: SideEnum) {
    var isConnected = true
        private set

    /** 마지막으로 연결이 끊긴 시각. 장기 이탈 판정에 쓴다. */
    var disconnectedAt: Instant? = null
        private set

    fun connect() {
        isConnected = true
        disconnectedAt = null
    }

    fun disconnect(now: Instant) {
        isConnected = false
        disconnectedAt = now
    }
}

data class RoomSpectator(val guestId: String, val nickname: String)

/** 게임 종류 + 초대코드 → 방 저장소 키. */
fun roomKeyOf(gameId: String, code: String): String = "$gameId:$code"

/**
 * 게임 방. DB 없이 메모리에만 둔다(ADR-0002). 게임 종류와 규칙을 모른다 — 수의 합법성은
 * 클라이언트가 판정하고(ADR-0001) 방은 차례 순서(선 후공 교대)와 시계, 기보 기록만 관리한다.
 * 스레드 안전하지 않으므로 호출하는 쪽에서 방 단위로 잠근다.
 */
class GameRoom(
    val code: String,
    val gameId: String,
    /** 재대국 직전에 방장이 시간 규칙을 바꿀 수 있다. */
    timeRule: TimeRule,
    private val hostPreference: SidePreferenceEnum,
    /** 시작 전 대국자별 준비(장기 상차림 등)가 필요한 방인지. */
    val isSetupRequired: Boolean,
    val createdAt: Instant,
    private val random: Random = Random.Default,
) {
    var timeRule = timeRule
        private set

    /** 방 저장소 키. 초대코드는 게임마다 따로라 게임 종류와 묶는다. */
    val key: String get() = roomKeyOf(gameId, code)

    /** 방장 게스트. 시간 규칙 변경 권한이 있다. */
    var hostGuestId: String? = null
        private set

    var phase = RoomPhaseEnum.WAITING
        private set

    var lastActivityAt = createdAt
        private set

    var result: RoomResult? = null
        private set

    private val rematchVotes = mutableSetOf<String>()
    private val players = mutableListOf<RoomPlayer>()
    private val spectators = mutableListOf<RoomSpectator>()
    private val moves = mutableListOf<RoomMove>()

    /** 직전 수 이후의 중간 행동 로그. 재접속한 대국자가 차례 진행 상황을 되찾는다(ADR-0004). */
    private val actions = ArrayDeque<RoomAction>()
    private val chatLog = ArrayDeque<ChatEntry>()
    private val lastChatAt = mutableMapOf<String, Instant>()
    private val setups = mutableMapOf<SideEnum, String>()
    private var clocks = mutableMapOf<SideEnum, PlayerClock>()

    /** 시계를 마지막으로 정산한 시각. 대국이 시작될 때 잡는다. */
    private var lastClockAt: Instant? = null

    /** 진영별 메이트할 기물 유무. 체스 클라이언트가 수마다 알려 준다(ADR-0001). */
    private val matingMaterial = mutableMapOf(SideEnum.WHITE to true, SideEnum.BLACK to true)

    val playerCount: Int get() = players.size
    val isFull: Boolean get() = players.size >= 2

    fun findPlayer(guestId: String): RoomPlayer? = players.firstOrNull { it.guestId == guestId }

    fun findSide(guestId: String): SideEnum? = findPlayer(guestId)?.side

    fun findSpectator(guestId: String): RoomSpectator? = spectators.firstOrNull { it.guestId == guestId }

    fun isParticipant(guestId: String): Boolean = findPlayer(guestId) != null || findSpectator(guestId) != null

    /**
     * 참가. 대국자 자리가 남아 있으면 대국자, 다 찼으면 관전자로 배정한다.
     * 관전자 정원까지 차면 거절한다. 같은 게스트의 재참가는 자리를 되찾는다.
     */
    fun join(guestId: String, nickname: String, now: Instant): JoinResult {
        touch(now)
        findPlayer(guestId)?.let { player ->
            if (!player.isConnected) {
                player.connect()
                addSystemChat("${player.nickname} 다시 연결", now)
            }
            return JoinResult(side = player.side, role = RoomRoleEnum.PLAYER, isAllowed = true)
        }
        if (findSpectator(guestId) != null) {
            return JoinResult(side = null, role = RoomRoleEnum.SPECTATOR, isAllowed = true)
        }
        if (isFull) {
            if (spectators.size >= SPECTATOR_LIMIT) {
                return JoinResult(side = null, role = RoomRoleEnum.SPECTATOR, isAllowed = false)
            }
            spectators.add(RoomSpectator(guestId = guestId, nickname = nickname))
            addSystemChat("$nickname 입장", now)
            return JoinResult(side = null, role = RoomRoleEnum.SPECTATOR, isAllowed = true)
        }
        val side = players.firstOrNull()?.side?.opposite() ?: hostPreference.pickSide(random)
        if (players.isEmpty()) hostGuestId = guestId
        players.add(RoomPlayer(guestId = guestId, nickname = nickname, side = side))
        addSystemChat("$nickname 입장", now)
        if (players.size == 2) {
            if (isSetupRequired) phase = RoomPhaseEnum.SETUP else start(now)
        }
        return JoinResult(side = side, role = RoomRoleEnum.PLAYER, isAllowed = true)
    }

    /** 연결이 끊겼다. 관전자는 방에서 빠지고, 대국자는 재접속할 수 있도록 남는다. */
    fun disconnect(guestId: String, now: Instant) {
        findPlayer(guestId)?.let { player ->
            player.disconnect(now)
            addSystemChat("${player.nickname} 연결 끊김", now)
            return
        }
        findSpectator(guestId)?.let { spectator ->
            spectators.remove(spectator)
            addSystemChat("${spectator.nickname} 퇴장", now)
        }
    }

    /** 방을 나간다. 대국자가 나가면 재대국을 할 수 없다. */
    fun leave(guestId: String, now: Instant) {
        rematchVotes.remove(guestId)
        val nickname = findPlayer(guestId)?.also { players.remove(it) }?.nickname
            ?: findSpectator(guestId)?.also { spectators.remove(it) }?.nickname
            ?: return
        addSystemChat("$nickname 나감", now)
    }

    /**
     * 채팅. 대국자와 관전자 모두 쓸 수 있고 1인당 1초에 1개까지.
     * 메시지는 200자까지 남긴다. 위반하면 null.
     */
    fun addChat(guestId: String, text: String, now: Instant): ChatEntry? {
        touch(now)
        val trimmed = text.trim().take(MAX_CHAT_LENGTH)
        if (trimmed.isEmpty()) return null
        val player = findPlayer(guestId)
        val nickname = player?.nickname ?: findSpectator(guestId)?.nickname ?: return null
        val last = lastChatAt[guestId]
        if (last != null && Duration.between(last, now) < CHAT_INTERVAL) return null
        lastChatAt[guestId] = now
        val entry = ChatEntry(nickname = nickname, text = trimmed, isFromPlayer = player != null, at = now)
        appendChat(entry)
        return entry
    }

    private fun addSystemChat(text: String, now: Instant) {
        appendChat(ChatEntry(nickname = "", text = text, isFromPlayer = false, isSystem = true, at = now))
    }

    private fun appendChat(entry: ChatEntry) {
        chatLog.addLast(entry)
        while (chatLog.size > CHAT_HISTORY) chatLog.removeFirst()
    }

    /** 시작 전 준비(장기 상차림). 두 대국자 모두 제출하면 대국이 시작된다. */
    fun submitSetup(guestId: String, pattern: String, now: Instant): Boolean {
        touch(now)
        if (phase != RoomPhaseEnum.SETUP) return false
        val side = findSide(guestId) ?: return false
        setups[side] = pattern
        if (setups.size == 2) start(now)
        return true
    }

    private fun start(now: Instant) {
        phase = RoomPhaseEnum.PLAYING
        clocks = mutableMapOf(SideEnum.WHITE to timeRule.initialClock(), SideEnum.BLACK to timeRule.initialClock())
        matingMaterial.keys.forEach { matingMaterial[it] = true }
        actions.clear()
        // 상차림 등 시작 전 준비에 쓴 시간은 시계에 넣지 않는다
        lastClockAt = now
        touch(now)
    }

    /** 지금 차례인 진영. 선공(white)부터 수 개수 홀짝으로 정한다. */
    fun turn(): SideEnum = if (moves.size % 2 == 0) SideEnum.WHITE else SideEnum.BLACK

    /**
     * 마지막 정산 이후 흐른 시간을 차례 진영 시계에 청구한다.
     * 시간패가 나면 대국을 끝내고 결과를 반환한다.
     */
    fun settleClock(now: Instant): RoomResult? {
        if (phase != RoomPhaseEnum.PLAYING) return null
        val since = lastClockAt ?: now
        lastClockAt = now
        if (timeRule.isUntimed) return null
        val elapsedMs = Duration.between(since, now).toMillis()
        if (elapsedMs <= 0) return null
        val side = turn()
        val next = clocks.getValue(side).tick(timeRule, elapsedMs)
        clocks[side] = next
        if (!next.isFlagged) return null
        val opponent = side.opposite()
        // 상대에게 메이트할 기물이 없으면 시간패 대신 무승부다
        val result = if (matingMaterial.getValue(opponent)) {
            RoomResult(winner = opponent, reason = GameEndReasonEnum.TIMEOUT.label)
        } else {
            RoomResult(winner = null, reason = GameEndReasonEnum.TIMEOUT_DRAW.label)
        }
        finish(result)
        return result
    }

    /**
     * 수를 기록한다. 서버는 규칙을 검증하지 않고(ADR-0001) 차례 순서만 지킨다.
     * notation 과 matingMaterial 은 둔 클라이언트가 계산해 실어 보낸 값이다.
     */
    fun addMove(
        guestId: String,
        from: String,
        to: String,
        notation: String,
        matingMaterial: Map<SideEnum, Boolean>,
        now: Instant,
    ): MoveResult {
        touch(now)
        if (phase != RoomPhaseEnum.PLAYING) return MoveResult.Rejected
        val side = findSide(guestId) ?: return MoveResult.Rejected
        if (side != turn()) return MoveResult.Rejected
        // 수를 받은 시각 기준으로 둔 사람의 시계를 먼저 정산한다(ADR-0001)
        settleClock(now)?.let { return MoveResult.TimedOut(it) }
        val move = RoomMove(side = side, from = from, to = to, notation = notation, at = now)
        moves.add(move)
        actions.clear()
        clocks[side] = clocks.getValue(side).afterMove(timeRule)
        this.matingMaterial.putAll(matingMaterial)
        return MoveResult.Moved(move)
    }

    /**
     * 차례 안의 중간 행동을 기록한다. 대국 중 차례인 대국자만 보낼 수 있고, 차례·시계는 바뀌지 않는다.
     * payload 는 해석하지 않는다(ADR-0004). 로그는 최근 [ACTION_LOG_LIMIT]개만 남긴다.
     */
    fun addAction(guestId: String, payload: Map<String, Any?>, now: Instant): RoomAction? {
        touch(now)
        if (phase != RoomPhaseEnum.PLAYING) return null
        val side = findSide(guestId) ?: return null
        if (side != turn()) return null
        val action = RoomAction(side = side, payload = payload, at = now)
        actions.addLast(action)
        while (actions.size > ACTION_LOG_LIMIT) actions.removeFirst()
        return action
    }

    /** 규칙 판정 결과(외통수·빅장 등)로 대국을 끝낸다. 판정은 클라이언트가 했다(ADR-0001). */
    fun finishWith(guestId: String, winner: SideEnum?, reason: String, now: Instant): RoomResult? {
        touch(now)
        if (phase != RoomPhaseEnum.PLAYING || findSide(guestId) == null) return null
        return RoomResult(winner = winner, reason = reason).also { finish(it) }
    }

    fun resign(guestId: String, now: Instant): RoomResult? {
        touch(now)
        if (phase != RoomPhaseEnum.PLAYING) return null
        val side = findSide(guestId) ?: return null
        return RoomResult(winner = side.opposite(), reason = GameEndReasonEnum.RESIGN.label).also { finish(it) }
    }

    /** 무제한 대국에서 상대 연결이 [ABANDON_LIMIT] 이상 끊겨 있으면 승리 처리한다. */
    fun claimAbandonWin(guestId: String, now: Instant): RoomResult? {
        touch(now)
        if (phase != RoomPhaseEnum.PLAYING || !timeRule.isUntimed) return null
        val side = findSide(guestId) ?: return null
        val opponent = players.firstOrNull { it.side == side.opposite() } ?: return null
        val disconnectedAt = opponent.disconnectedAt ?: return null
        if (Duration.between(disconnectedAt, now) < ABANDON_LIMIT) return null
        return RoomResult(winner = side, reason = GameEndReasonEnum.ABANDON.label).also { finish(it) }
    }

    private fun finish(result: RoomResult) {
        this.result = result
        phase = RoomPhaseEnum.FINISHED
    }

    /**
     * 재대국 투표. 끝난 방에서 양쪽 대국자가 모두 눌러야 새 대국이 시작된다.
     * 대국자가 한 명이라도 방을 나가면 더 이상 할 수 없다. 시작되면 true.
     */
    fun requestRematch(guestId: String, now: Instant): Boolean {
        touch(now)
        if (phase != RoomPhaseEnum.FINISHED || players.size < 2) return false
        findPlayer(guestId) ?: return false
        rematchVotes.add(guestId)
        if (rematchVotes != players.map { it.guestId }.toSet()) return false
        startRematch(now)
        return true
    }

    private fun startRematch(now: Instant) {
        // 진영 교대: 백과 흑이 서로 바뀐다
        players.forEach { it.side = it.side.opposite() }
        moves.clear()
        actions.clear()
        rematchVotes.clear()
        result = null
        if (isSetupRequired) {
            // 상차림을 다시 고른다. 시계는 상차림이 끝나 대국이 시작될 때 잡는다.
            setups.clear()
            phase = RoomPhaseEnum.SETUP
            touch(now) // 방 수명 타이머를 처음부터 다시 잰다
            return
        }
        start(now)
    }

    /** 끝난 방에서 방장이 시간 규칙을 바꾼다(재대국 시작 전까지). */
    fun updateTimeRule(guestId: String, rule: TimeRule, now: Instant): Boolean {
        touch(now)
        if (phase != RoomPhaseEnum.FINISHED || hostGuestId != guestId) return false
        timeRule = rule
        return true
    }

    /** 방 수명 정리 대상인지. [now] 기준으로 판단한다. */
    fun isExpired(now: Instant): Boolean {
        val idle = Duration.between(lastActivityAt, now)
        return when {
            phase == RoomPhaseEnum.WAITING && Duration.between(createdAt, now) > WAITING_LIMIT -> true
            phase == RoomPhaseEnum.FINISHED && idle > FINISHED_LIMIT -> true
            else -> idle > IDLE_LIMIT
        }
    }

    private fun touch(now: Instant) {
        lastActivityAt = now
    }

    fun setupSnapshot(): Map<SideEnum, String> = setups.toMap()

    fun clockSnapshot(): Map<SideEnum, PlayerClock> = clocks.toMap()

    fun movesSnapshot(): List<RoomMove> = moves.toList()

    fun actionsSnapshot(): List<RoomAction> = actions.toList()

    fun playersSnapshot(): List<RoomPlayer> = players.toList()

    fun spectatorsSnapshot(): List<RoomSpectator> = spectators.toList()

    fun chatSnapshot(): List<ChatEntry> = chatLog.toList()

    /** 재대국 투표 현황(닉네임). */
    fun rematchVotesSnapshot(): List<String> = rematchVotes.map { findPlayer(it)?.nickname ?: it }

    companion object {
        const val SPECTATOR_LIMIT = 10
        const val CHAT_HISTORY = 50
        const val MAX_CHAT_LENGTH = 200
        const val ACTION_LOG_LIMIT = 64
        private val CHAT_INTERVAL = Duration.ofSeconds(1)
        val ABANDON_LIMIT: Duration = Duration.ofMinutes(5)
        private val WAITING_LIMIT = Duration.ofMinutes(30)
        private val FINISHED_LIMIT = Duration.ofMinutes(10)
        private val IDLE_LIMIT = Duration.ofMinutes(30)
    }
}
