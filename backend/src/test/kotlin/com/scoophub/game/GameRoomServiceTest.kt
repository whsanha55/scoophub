package com.scoophub.game

import com.scoophub.game.dto.CreateRoomCommand
import com.scoophub.game.enums.GameEndReasonEnum
import com.scoophub.game.enums.RoomPhaseEnum
import com.scoophub.game.enums.RoomRoleEnum
import com.scoophub.game.enums.SideEnum
import com.scoophub.game.service.DefaultAbuseLimiter
import com.scoophub.game.service.GameRoom
import com.scoophub.game.service.GameRoomService
import com.scoophub.game.vo.TimeRule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** 조작 가능한 시계 — 서버 시계 판정 테스트용. */
class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(ms: Long) {
        now = now.plusMillis(ms)
    }
}

class GameRoomServiceTest {
    private val clock = MutableClock()
    private val limiter = DefaultAbuseLimiter(clock)
    private val service = GameRoomService(clock, limiter, ApplicationEventPublisher { })

    @AfterEach
    fun tearDown() {
        service.close()
    }

    private fun createRoom(
        guestId: String = "host",
        ip: String = "1.1.1.1",
        rule: TimeRule = TimeRule(60_000, 10_000, 1),
        sidePreference: String = "white",
        gameId: String = "chess",
        isSetupRequired: Boolean = false,
    ): GameRoom = service.createRoom(
        CreateRoomCommand(
            gameId = gameId,
            baseMs = rule.baseMs,
            byoYomiMs = rule.byoYomiMs,
            byoYomiCount = rule.byoYomiCount,
            sidePreference = sidePreference,
            guestId = guestId,
            nickname = "방장",
            isSetupRequired = isSetupRequired,
            ip = ip,
        ),
    )

    /** 방장(백)과 상대(흑)가 모여 대국이 시작된 방 */
    private fun startedRoom(rule: TimeRule = TimeRule(60_000, 10_000, 1)): GameRoom {
        val room = createRoom(rule = rule)
        service.join(room, "guest2", "상대")
        return room
    }

    /** 장기처럼 상차림이 필요한 방. 두 대국자가 모여 상차림 단계다. */
    private fun setupRoom(rule: TimeRule = TimeRule(300_000, 30_000, 3)): GameRoom {
        val room = createRoom(guestId = "h", rule = rule, gameId = "janggi", isSetupRequired = true)
        service.join(room, "g2", "한")
        return room
    }

    private fun assertTooManyRequests(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `방을 만들면 코드 6자리이고 방장이 입장해 있다`() {
        // given
        // when
        val room = createRoom()

        // then
        assertThat(room.code).hasSize(6).containsOnlyDigits()
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.WAITING)
        assertThat(room.findSide("host")).isEqualTo(SideEnum.WHITE)
    }

    @Test
    fun `초대코드는 게임마다 따로다 - 다른 게임으로는 같은 코드의 방을 찾지 못한다`() {
        // given
        val room = createRoom(gameId = "chess")

        // when
        val asChess = service.findRoom("chess", room.code)
        val asJanggi = service.findRoom("janggi", room.code)

        // then
        assertThat(asChess).isSameAs(room)
        assertThat(asJanggi).isNull()
    }

    @Test
    fun `방을 만들 때 시간 규칙은 상한 안으로 맞춘다`() {
        // given
        val rule = TimeRule(baseMs = 999 * 60_000L, byoYomiMs = 999_000, byoYomiCount = 99)

        // when
        val room = createRoom(rule = rule)

        // then
        assertThat(
            room.timeRule,
        ).isEqualTo(TimeRule(TimeRule.MAX_BASE_MS, TimeRule.MAX_BYOYOMI_MS, TimeRule.MAX_BYOYOMI_COUNT))
    }

    @Test
    fun `두 대국자가 모이면 대국이 시작된다`() {
        // given
        val room = createRoom(sidePreference = "white")

        // when
        val join = service.join(room, "guest2", "상대")

        // then
        assertThat(join.side).isEqualTo(SideEnum.BLACK)
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.PLAYING)
    }

    @Test
    fun `정원이 찬 방에 들어오면 관전자로 배정된다`() {
        // given
        val room = startedRoom()

        // when
        val join = service.join(room, "guest3", "늦은이")

        // then
        assertThat(join.role).isEqualTo(RoomRoleEnum.SPECTATOR)
    }

    @Test
    fun `같은 게스트가 다시 들어오면 자리를 되찾는다`() {
        // given
        val room = startedRoom()

        // when
        val again = service.join(room, "host", "방장")

        // then
        assertThat(again.side).isEqualTo(SideEnum.WHITE)
        assertThat(again.role).isEqualTo(RoomRoleEnum.PLAYER)
        assertThat(room.playerCount).isEqualTo(2)
    }

    @Test
    fun `차례가 아니면 수가 거절된다`() {
        // given
        val room = startedRoom()

        // when
        val blackFirst = service.addMove(room, "guest2", "e7", "e5", "e5")
        val whiteMove = service.addMove(room, "host", "e2", "e4", "e4")
        val whiteAgain = service.addMove(room, "host", "d2", "d4", "d4")
        val blackMove = service.addMove(room, "guest2", "e7", "e5", "e5")

        // then
        assertThat(blackFirst).isNull()
        assertThat(whiteMove).isNotNull
        assertThat(whiteAgain).isNull()
        assertThat(blackMove).isNotNull
        assertThat(room.movesSnapshot().map { it.notation }).containsExactly("e4", "e5")
    }

    @Test
    fun `차례가 아닌 사람이 수를 보내도 시계가 깎이지 않는다`() {
        // given
        val room = startedRoom(rule = TimeRule(baseMs = 60_000, byoYomiMs = 0, byoYomiCount = 0))
        clock.advance(10_000)

        // when
        service.addMove(room, "guest2", "e7", "e5", "e5")

        // then — 흑 시계는 그대로이고, 백이 쓴 10초는 백이 둘 때 청구된다
        assertThat(room.clockSnapshot().getValue(SideEnum.BLACK).baseLeftMs).isEqualTo(60_000)
        service.addMove(room, "host", "e2", "e4", "e4")
        assertThat(room.clockSnapshot().getValue(SideEnum.WHITE).baseLeftMs).isEqualTo(50_000)
    }

    @Test
    fun `기권하면 상대가 이기고 더 둘 수 없다`() {
        // given
        val room = startedRoom()

        // when
        val result = service.resign(room, "guest2")

        // then
        assertThat(result).isNotNull
        assertThat(result!!.winner).isEqualTo(SideEnum.WHITE)
        assertThat(result.reason).isEqualTo(GameEndReasonEnum.RESIGN.label)
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.FINISHED)
        assertThat(service.addMove(room, "host", "e2", "e4", "e4")).isNull()
    }

    @Test
    fun `수를 받은 시각 기준으로 시계가 정산되고 시간패가 난다`() {
        // given — 백 3초, 흑 3초
        val room = startedRoom(rule = TimeRule(baseMs = 3_000, byoYomiMs = 0, byoYomiCount = 0))
        clock.advance(2_000)
        service.addMove(room, "host", "e2", "e4", "e4")
        clock.advance(2_000)
        service.addMove(room, "guest2", "e7", "e5", "e5")

        // when — 백 남은 1초를 넘겨 2초 더 생각한다
        clock.advance(2_000)
        val move = service.addMove(room, "host", "g1", "f3", "Nf3")

        // then
        assertThat(move).isNull()
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.FINISHED)
        assertThat(room.result!!.winner).isEqualTo(SideEnum.BLACK)
        assertThat(room.result!!.reason).isEqualTo(GameEndReasonEnum.TIMEOUT.label)
    }

    @Test
    fun `초읽기에서는 넘길 때마다 횟수가 줄고 다 소진하면 시간패`() {
        // given — 초읽기 5초, 1번 넘길 수 있다
        val room = startedRoom(rule = TimeRule(baseMs = 0, byoYomiMs = 5_000, byoYomiCount = 1))
        clock.advance(7_000)
        service.addMove(room, "host", "e2", "e4", "e4")
        clock.advance(6_000)
        service.addMove(room, "guest2", "e7", "e5", "e5")

        // when
        clock.advance(5_000)
        val move = service.addMove(room, "host", "d2", "d4", "d4")

        // then
        assertThat(move).isNull()
        assertThat(room.result!!.winner).isEqualTo(SideEnum.BLACK)
    }

    @Test
    fun `시간패한 쪽의 상대에게 메이트할 기물이 없으면 무승부다`() {
        // given — 흑이 수를 두며 흑에게 메이트할 기물이 없다고 알렸다
        val room = startedRoom(rule = TimeRule(baseMs = 3_000, byoYomiMs = 0, byoYomiCount = 0))
        service.addMove(room, "host", "e2", "e4", "e4")
        service.addMove(room, "guest2", "e7", "e5", "e5", mapOf(SideEnum.WHITE to true, SideEnum.BLACK to false))

        // when — 백이 시간을 넘긴다
        clock.advance(4_000)
        val result = room.settleClock(clock.now)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.winner).isNull()
        assertThat(result.reason).isEqualTo(GameEndReasonEnum.TIMEOUT_DRAW.label)
    }

    @Test
    fun `시계 정산만으로도 활성 시계가 깎이고 시간패가 난다`() {
        // given
        val room = startedRoom(rule = TimeRule(baseMs = 1_000, byoYomiMs = 0, byoYomiCount = 0))

        // when
        clock.advance(1_500)
        val result = room.settleClock(clock.now)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.reason).isEqualTo(GameEndReasonEnum.TIMEOUT.label)
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.FINISHED)
    }

    @Test
    fun `무제한 대국은 시간패가 나지 않는다`() {
        // given
        val room = startedRoom(rule = TimeRule.untimed())

        // when
        clock.advance(3_600_000)
        val result = room.settleClock(clock.now)

        // then
        assertThat(result).isNull()
        assertThat(service.addMove(room, "host", "e2", "e4", "e4")).isNotNull
    }

    @Test
    fun `무제한 대국에서 상대 연결이 5분 이상 끊기면 승리 처리`() {
        // given
        val room = startedRoom(rule = TimeRule.untimed())
        service.disconnect(room, "guest2")

        // when
        clock.advance(4 * 60_000)
        val tooEarly = service.claimAbandonWin(room, "host")
        clock.advance(2 * 60_000)
        val result = service.claimAbandonWin(room, "host")

        // then
        assertThat(tooEarly).isNull()
        assertThat(result).isNotNull
        assertThat(result!!.winner).isEqualTo(SideEnum.WHITE)
        assertThat(result.reason).isEqualTo(GameEndReasonEnum.ABANDON.label)
    }

    @Test
    fun `연결된 상대는 오래 수를 두지 않아도 이탈로 보지 않는다`() {
        // given — 상대는 연결된 채 10분째 생각 중이다
        val room = startedRoom(rule = TimeRule.untimed())
        clock.advance(10 * 60_000)

        // when
        val result = service.claimAbandonWin(room, "host")

        // then
        assertThat(result).isNull()
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.PLAYING)
    }

    @Test
    fun `끊겼던 상대가 다시 붙으면 이탈 시간이 초기화된다`() {
        // given
        val room = startedRoom(rule = TimeRule.untimed())
        service.disconnect(room, "guest2")
        clock.advance(4 * 60_000)
        service.join(room, "guest2", "상대")

        // when
        clock.advance(2 * 60_000)
        val result = service.claimAbandonWin(room, "host")

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `방 수명 - 대기 30분 지나면 정리 대상`() {
        // given
        val room = createRoom()

        // when
        val before = room.isExpired(clock.now)
        clock.advance(31 * 60_000)

        // then
        assertThat(before).isFalse()
        assertThat(room.isExpired(clock.now)).isTrue()
    }

    @Test
    fun `방 수명 - 종료 후 10분 지나면 정리 대상`() {
        // given
        val room = startedRoom()
        service.resign(room, "host")

        // when
        clock.advance(9 * 60_000)
        val before = room.isExpired(clock.now)
        clock.advance(2 * 60_000)

        // then
        assertThat(before).isFalse()
        assertThat(room.isExpired(clock.now)).isTrue()
    }

    @Test
    fun `방 수명 - 무활동 30분이면 진행 중이어도 정리 대상`() {
        // given
        val room = startedRoom()

        // when
        clock.advance(29 * 60_000)
        val before = room.isExpired(clock.now)
        clock.advance(2 * 60_000)

        // then
        assertThat(before).isFalse()
        assertThat(room.isExpired(clock.now)).isTrue()
    }

    @Test
    fun `한도 - 게스트당 3개 방`() {
        // given
        repeat(3) { createRoom(guestId = "g1") }

        // when
        // then
        assertTooManyRequests { createRoom(guestId = "g1") }
    }

    @Test
    fun `한도 - IP당 20개 방`() {
        // given
        repeat(20) { i -> createRoom(guestId = "guest$i", ip = "2.2.2.2") }

        // when
        // then
        assertTooManyRequests { createRoom(guestId = "newguest", ip = "2.2.2.2") }
    }

    @Test
    fun `한도 - 초대코드 확인은 IP당 분당 10번`() {
        // given
        repeat(10) { service.validateCodeAttempt("3.3.3.3") }

        // when
        // then
        assertTooManyRequests { service.validateCodeAttempt("3.3.3.3") }
        service.validateCodeAttempt("4.4.4.4")
        clock.advance(61_000)
        service.validateCodeAttempt("3.3.3.3")
    }

    @Test
    fun `한도 - WS로 처음 들어오는 연결도 초대코드 시도로 센다`() {
        // given
        repeat(10) { i -> service.findRoomToConnect("chess", "00000$i", "stranger", "5.5.5.5") }

        // when
        // then
        assertTooManyRequests { service.findRoomToConnect("chess", "999999", "stranger", "5.5.5.5") }
    }

    @Test
    fun `한도 - 방에 있는 게스트의 WS 재접속은 세지 않는다`() {
        // given
        val room = startedRoom()
        repeat(10) { service.validateCodeAttempt("6.6.6.6") }

        // when
        val found = service.findRoomToConnect(room.gameId, room.code, "guest2", "6.6.6.6")

        // then
        assertThat(found).isSameAs(room)
    }

    @Test
    fun `방을 닫으면 게스트 한도가 돌아온다`() {
        // given
        val rooms = (0 until 3).map { createRoom(guestId = "g1") }

        // when
        service.closeRoom(rooms.first())

        // then
        createRoom(guestId = "g1")
    }

    @Test
    fun `방을 닫으면 IP 한도가 돌아온다`() {
        // given
        val rooms = (0 until 20).map { i -> createRoom(guestId = "guest$i", ip = "7.7.7.7") }

        // when
        service.closeRoom(rooms.first())

        // then
        createRoom(guestId = "another", ip = "7.7.7.7")
    }

    @Test
    fun `관전자는 방당 최대 10명이다`() {
        // given
        val room = startedRoom()
        repeat(10) { i -> service.join(room, "spec$i", "관전$i") }

        // when
        val join = service.join(room, "spec11", "넘침")

        // then
        assertThat(join.isAllowed).isFalse()
        assertThat(room.spectatorsSnapshot()).hasSize(10)
    }

    @Test
    fun `관전자는 연결이 끊기면 빠지고 다시 들어오면 관전자다`() {
        // given
        val room = startedRoom()
        service.join(room, "spec1", "관전1")
        service.disconnect(room, "spec1")

        // when
        val again = service.join(room, "spec1", "관전1")

        // then
        assertThat(again.role).isEqualTo(RoomRoleEnum.SPECTATOR)
        assertThat(room.spectatorsSnapshot().map { it.guestId }).contains("spec1")
    }

    @Test
    fun `대국자와 관전자 모두 채팅할 수 있고 표시가 갈린다`() {
        // given
        val room = startedRoom()
        service.join(room, "spec1", "관전1")

        // when
        val fromPlayer = service.chat(room, "host", "안녕")
        val fromSpectator = service.chat(room, "spec1", "관전입니다")

        // then
        assertThat(fromPlayer!!.isFromPlayer).isTrue()
        assertThat(fromSpectator!!.isFromPlayer).isFalse()
    }

    @Test
    fun `채팅은 1인당 1초에 1개까지`() {
        // given
        val room = startedRoom()
        service.chat(room, "host", "첫 번째")

        // when
        val tooSoon = service.chat(room, "host", "두 번째")
        val other = service.chat(room, "guest2", "다른 사람은 가능")
        clock.advance(1_000)
        val later = service.chat(room, "host", "1초 뒤")

        // then
        assertThat(tooSoon).isNull()
        assertThat(other).isNotNull
        assertThat(later).isNotNull
    }

    @Test
    fun `채팅은 200자까지이고 방에 없는 사람은 못 쓴다`() {
        // given
        val room = startedRoom()

        // when
        val long = service.chat(room, "host", "가".repeat(201))
        clock.advance(1_100)
        val stranger = service.chat(room, "stranger", "외부인")
        val blank = service.chat(room, "host", "   ")

        // then
        assertThat(long!!.text).hasSize(200)
        assertThat(stranger).isNull()
        assertThat(blank).isNull()
    }

    @Test
    fun `관전자 입장과 퇴장이 채팅에 알림으로 남는다`() {
        // given
        val room = startedRoom()

        // when
        service.join(room, "spec1", "관전1")
        service.disconnect(room, "spec1")

        // then
        assertThat(room.chatSnapshot().map { it.text }).contains("관전1 입장", "관전1 퇴장")
        assertThat(room.chatSnapshot()).allMatch { it.isSystem }
    }

    @Test
    fun `대국자 연결이 끊기고 다시 붙으면 알림이 남고 자리는 유지된다`() {
        // given
        val room = startedRoom()

        // when
        service.disconnect(room, "guest2")
        val again = service.join(room, "guest2", "상대")

        // then
        assertThat(again.side).isEqualTo(SideEnum.BLACK)
        assertThat(room.chatSnapshot().map { it.text }).contains("상대 연결 끊김", "상대 다시 연결")
    }

    @Test
    fun `채팅 기록은 최근 50개만 남는다`() {
        // given
        val room = startedRoom()

        // when
        for (i in 1..60) {
            clock.advance(1_100)
            service.chat(room, "host", "msg$i")
        }

        // then
        assertThat(room.chatSnapshot()).hasSize(50)
        assertThat(room.chatSnapshot().last().text).isEqualTo("msg60")
    }

    @Test
    fun `재대국은 양쪽 모두 동의해야 시작된다`() {
        // given
        val room = startedRoom()
        service.resign(room, "host")

        // when
        val oneVote = service.requestRematch(room, "host")
        val phaseAfterOne = room.phase
        val bothVotes = service.requestRematch(room, "guest2")

        // then
        assertThat(oneVote).isFalse()
        assertThat(phaseAfterOne).isEqualTo(RoomPhaseEnum.FINISHED)
        assertThat(bothVotes).isTrue()
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.PLAYING)
        assertThat(room.movesSnapshot()).isEmpty()
        assertThat(room.result).isNull()
    }

    @Test
    fun `재대국하면 진영이 서로 바뀐다`() {
        // given
        val room = startedRoom()
        service.resign(room, "guest2")

        // when
        service.requestRematch(room, "host")
        service.requestRematch(room, "guest2")

        // then
        assertThat(room.findSide("host")).isEqualTo(SideEnum.BLACK)
        assertThat(room.findSide("guest2")).isEqualTo(SideEnum.WHITE)
    }

    @Test
    fun `재대국 시작 시 시계와 방 수명이 갱신된다`() {
        // given
        val room = startedRoom(rule = TimeRule(60_000, 0, 0))
        clock.advance(20 * 60_000)
        service.resign(room, "host")
        clock.advance(9 * 60_000)

        // when
        service.requestRematch(room, "host")
        service.requestRematch(room, "guest2")
        service.resign(room, "host")
        clock.advance(9 * 60_000)
        val before = room.isExpired(clock.now)
        clock.advance(2 * 60_000)

        // then
        assertThat(before).isFalse()
        assertThat(room.isExpired(clock.now)).isTrue()
        assertThat(room.clockSnapshot().getValue(SideEnum.WHITE).baseLeftMs).isEqualTo(60_000)
    }

    @Test
    fun `대국자가 나가면 재대국을 할 수 없다`() {
        // given
        val room = startedRoom()
        service.resign(room, "host")

        // when
        service.leave(room, "guest2")
        val isStarted = service.requestRematch(room, "host")

        // then
        assertThat(room.findPlayer("guest2")).isNull()
        assertThat(isStarted).isFalse()
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.FINISHED)
    }

    @Test
    fun `재대국 직전 방장만 시간 규칙을 바꿀 수 있다`() {
        // given
        val room = startedRoom()
        service.resign(room, "host")

        // when
        val byGuest = service.updateTimeRule(room, "guest2", 5 * 60_000, 10_000, 3)
        val byHost = service.updateTimeRule(room, "host", 5 * 60_000, 10_000, 3)
        service.requestRematch(room, "host")
        service.requestRematch(room, "guest2")

        // then
        assertThat(byGuest).isFalse()
        assertThat(byHost).isTrue()
        assertThat(room.clockSnapshot().getValue(SideEnum.WHITE).baseLeftMs).isEqualTo(5 * 60_000)
        assertThat(room.clockSnapshot().getValue(SideEnum.WHITE).byoYomiLeft).isEqualTo(3)
    }

    @Test
    fun `재대국 시간 규칙도 상한 안으로 맞춘다`() {
        // given
        val room = startedRoom()
        service.resign(room, "host")

        // when
        service.updateTimeRule(room, "host", 999 * 60_000L, 999_000, 99)

        // then
        assertThat(
            room.timeRule,
        ).isEqualTo(TimeRule(TimeRule.MAX_BASE_MS, TimeRule.MAX_BYOYOMI_MS, TimeRule.MAX_BYOYOMI_COUNT))
    }

    @Test
    fun `상차림 방 - 두 대국자 모두 제출하면 시작된다`() {
        // given
        val room = setupRoom()

        // when
        val earlyMove = service.addMove(room, "h", "9,1", "7,2", "마")
        service.submitSetup(room, "h", "마상마상")
        val phaseAfterOne = room.phase
        service.submitSetup(room, "g2", "상마상마")

        // then
        assertThat(earlyMove).isNull()
        assertThat(phaseAfterOne).isEqualTo(RoomPhaseEnum.SETUP)
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.PLAYING)
        assertThat(room.setupSnapshot()[SideEnum.WHITE]).isEqualTo("마상마상")
        assertThat(room.setupSnapshot()[SideEnum.BLACK]).isEqualTo("상마상마")
    }

    @Test
    fun `상차림 방 재대국 - 진영 교대 후 상차림을 다시 고른다`() {
        // given
        val room = setupRoom()
        service.submitSetup(room, "h", "마상마상")
        service.submitSetup(room, "g2", "상마상마")
        service.resign(room, "h")

        // when
        service.requestRematch(room, "h")
        service.requestRematch(room, "g2")
        val phaseAfterRematch = room.phase
        val setupsAfterRematch = room.setupSnapshot()
        service.submitSetup(room, "h", "마상상마")
        service.submitSetup(room, "g2", "상마마상")

        // then
        assertThat(phaseAfterRematch).isEqualTo(RoomPhaseEnum.SETUP)
        assertThat(setupsAfterRematch).isEmpty()
        assertThat(room.findSide("h")).isEqualTo(SideEnum.BLACK)
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.PLAYING)
        assertThat(room.setupSnapshot()[SideEnum.WHITE]).isEqualTo("상마마상")
    }

    @Test
    fun `상차림 방 재대국 - 상차림을 고르는 데 쓴 시간은 시계에서 빠지지 않는다`() {
        // given
        val room = setupRoom(rule = TimeRule(baseMs = 60_000, byoYomiMs = 0, byoYomiCount = 0))
        service.submitSetup(room, "h", "마상마상")
        service.submitSetup(room, "g2", "상마상마")
        service.resign(room, "h")
        service.requestRematch(room, "h")
        service.requestRematch(room, "g2")

        // when — 상차림을 고르는 데 40초 걸렸다
        clock.advance(40_000)
        service.submitSetup(room, "h", "마상상마")
        service.submitSetup(room, "g2", "상마마상")
        clock.advance(5_000)
        room.settleClock(clock.now)

        // then — 초는 시작 뒤 5초만 쓴다
        assertThat(room.clockSnapshot().getValue(SideEnum.WHITE).baseLeftMs).isEqualTo(55_000)
    }

    @Test
    fun `상차림 없는 방은 두 명이 모이면 바로 시작되고 상차림을 받지 않는다`() {
        // given
        val room = startedRoom()

        // when
        val isAccepted = service.submitSetup(room, "host", "마상마상")

        // then
        assertThat(room.phase).isEqualTo(RoomPhaseEnum.PLAYING)
        assertThat(isAccepted).isFalse()
    }
}
