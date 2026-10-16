package com.scoophub.game

import com.scoophub.game.domain.GameRoom
import com.scoophub.game.domain.Role
import com.scoophub.game.domain.RoomPhase
import com.scoophub.game.domain.Side
import com.scoophub.game.domain.SidePreference
import com.scoophub.game.domain.TimeRule
import com.scoophub.game.service.DefaultAbuseLimiter
import com.scoophub.game.service.GameRoomService
import com.scoophub.game.service.RoomLimitException
import com.scoophub.game.service.RoomLookup
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
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
    private val service = GameRoomService(clock, limiter)

    private fun makeRoom(
        rule: TimeRule = TimeRule(60_000, 10_000, 1),
        preference: SidePreference = SidePreference.WHITE,
    ): GameRoom {
        val room = service.createRoom(
            gameId = "chess",
            timeRule = rule,
            preference = preference,
            guestId = "host",
            nickname = "방장",
            ip = "1.1.1.1",
        )
        return room
    }

    @Test
    fun `방을 만들면 코드 6자리이고 방장이 입장해 있다`() {
        val room = makeRoom()
        assertThat(room.code).hasSize(6).containsOnlyDigits()
        assertThat(room.phase).isEqualTo(RoomPhase.WAITING)
        assertThat(room.sideOf("host")).isEqualTo(Side.WHITE)
    }

    @Test
    fun `두 대국자가 모이면 대국이 시작된다`() {
        val room = makeRoom(preference = SidePreference.WHITE)
        val join = service.join(room.code, "guest2", "상대")
        assertThat(join.side).isEqualTo(Side.BLACK)
        assertThat(room.phase).isEqualTo(RoomPhase.PLAYING)
    }

    @Test
    fun `정원이 찬 방에는 대국자로 들어올 수 없다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        // 대국자 정원 초과 → 관전자로 배정된다
        assertThat(service.join(room.code, "guest3", "늦은이").role).isEqualTo(Role.SPECTATOR)
    }

    @Test
    fun `같은 게스트가 다시 들어오면 자리를 되찾는다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        val again = service.join(room.code, "host", "방장")
        assertThat(again.side).isEqualTo(Side.WHITE)
        assertThat(again.role).isEqualTo(Role.PLAYER)
        assertThat(room.playerCount).isEqualTo(2)
    }

    @Test
    fun `차례가 아니면 수가 거절된다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        // 백 차례에 흑이 두려 하면 거절
        assertThat(service.addMove(room, "guest2", "e7", "e5", "e5")).isNull()
        // 백이 두면 기록된다
        assertThat(service.addMove(room, "host", "e2", "e4", "e4")).isNotNull
        // 이제 백은 거절, 흑 가능
        assertThat(service.addMove(room, "host", "d2", "d4", "d4")).isNull()
        assertThat(service.addMove(room, "guest2", "e7", "e5", "e5")).isNotNull
        assertThat(room.movesSnapshot().map { it.notation }).containsExactly("e4", "e5")
    }

    @Test
    fun `기권하면 상대가 이긴다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        val result = service.resign(room, "guest2")
        assertThat(result).isNotNull
        assertThat(result!!.winner).isEqualTo(Side.WHITE)
        assertThat(result.reason).isEqualTo("기권")
        assertThat(room.phase).isEqualTo(RoomPhase.FINISHED)
        // 끝난 방에는 더 둘 수 없다
        assertThat(service.addMove(room, "host", "e2", "e4", "e4")).isNull()
    }

    @Test
    fun `수를 받은 시각 기준으로 시계가 정산되고 시간패가 난다`() {
        val room = makeRoom(rule = TimeRule(baseMs = 3_000, byoYomiMs = 0, byoYomiCount = 0))
        service.join(room.code, "guest2", "상대")
        clock.advance(2_000)
        assertThat(service.addMove(room, "host", "e2", "e4", "e4")).isNotNull
        // 흑 차례 — 2초 흐른 뒤 다시 백 차례로
        clock.advance(2_000)
        assertThat(service.addMove(room, "guest2", "e7", "e5", "e5")).isNotNull
        // 백 기본 시간 1초 남음: 2초 더 생각하면 시간패
        clock.advance(2_000)
        assertThat(service.addMove(room, "host", "g1", "f3", "Nf3")).isNull()
        assertThat(room.phase).isEqualTo(RoomPhase.FINISHED)
        assertThat(room.result!!.winner).isEqualTo(Side.BLACK)
        assertThat(room.result!!.reason).isEqualTo("시간패")
    }

    @Test
    fun `초읽기에서는 넘길 때마다 횟수가 줄고 다 소진하면 시간패`() {
        val room = makeRoom(rule = TimeRule(baseMs = 0, byoYomiMs = 5_000, byoYomiCount = 1))
        service.join(room.code, "guest2", "상대")
        // 백이 7초 쓰면 첫 초읽기 5초 초과 → 넘김 1회 소비
        clock.advance(7_000)
        assertThat(service.addMove(room, "host", "e2", "e4", "e4")).isNotNull
        assertThat(room.clockSnapshot().getValue(Side.WHITE).byoYomiLeft).isEqualTo(0)
        // 다시 5초 이상 쓰면 시간패
        clock.advance(6_000)
        assertThat(service.addMove(room, "guest2", "e7", "e5", "e5")).isNotNull // 흑 정상
        clock.advance(5_000)
        assertThat(service.addMove(room, "host", "d2", "d4", "d4")).isNull()
        assertThat(room.result!!.winner).isEqualTo(Side.BLACK)
    }

    @Test
    fun `틱으로도 활성 시계가 깎이고 시간패가 난다`() {
        val room = makeRoom(rule = TimeRule(baseMs = 1_000, byoYomiMs = 0, byoYomiCount = 0))
        service.join(room.code, "guest2", "상대")
        val result = room.tickActiveClock(1_500)
        assertThat(result).isNotNull
        assertThat(result!!.reason).isEqualTo("시간패")
        assertThat(room.phase).isEqualTo(RoomPhase.FINISHED)
    }

    @Test
    fun `무제한 대국은 시간패가 나지 않는다`() {
        val room = makeRoom(rule = TimeRule.untimed())
        service.join(room.code, "guest2", "상대")
        clock.advance(3_600_000)
        assertThat(room.tickActiveClock(3_600_000)).isNull()
        assertThat(service.addMove(room, "host", "e2", "e4", "e4")).isNotNull
    }

    @Test
    fun `무제한 대국에서 상대가 5분 이상 끊기면 승리 처리`() {
        val room = makeRoom(rule = TimeRule.untimed())
        service.join(room.code, "guest2", "상대")
        // 상대 마지막 접속 후 5분 미만 → 거절
        clock.advance(4 * 60_000)
        assertThat(service.claimAbandonWin(room, "host")).isNull()
        clock.advance(2 * 60_000) // 누적 6분
        val result = service.claimAbandonWin(room, "host")
        assertThat(result).isNotNull
        assertThat(result!!.winner).isEqualTo(Side.WHITE)
        assertThat(result.reason).isEqualTo("상대 장기 이탈")
    }

    @Test
    fun `방 수명 - 대기 30분 지나면 정리 대상`() {
        val room = makeRoom()
        assertThat(room.expired(clock.now)).isFalse()
        clock.advance(31 * 60_000)
        assertThat(room.expired(clock.now)).isTrue()
    }

    @Test
    fun `방 수명 - 종료 후 10분 지나면 정리 대상`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        service.resign(room, "host")
        clock.advance(9 * 60_000)
        assertThat(room.expired(clock.now)).isFalse()
        clock.advance(2 * 60_000)
        assertThat(room.expired(clock.now)).isTrue()
    }

    @Test
    fun `방 수명 - 무활동 30분이면 진행 중이어도 정리 대상`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        clock.advance(29 * 60_000)
        assertThat(room.expired(clock.now)).isFalse()
        clock.advance(2 * 60_000)
        assertThat(room.expired(clock.now)).isTrue()
    }

    @Test
    fun `한도 - 게스트당 3개 방`() {
        repeat(3) { i ->
            service.createRoom("chess", TimeRule.untimed(), SidePreference.RANDOM, "g1", "닉$i", "1.1.1.1")
        }
        assertThatThrownBy {
            service.createRoom("chess", TimeRule.untimed(), SidePreference.RANDOM, "g1", "닉", "1.1.1.1")
        }.isInstanceOf(RoomLimitException::class.java)
    }

    @Test
    fun `한도 - IP당 20개 방`() {
        repeat(20) { i ->
            service.createRoom("chess", TimeRule.untimed(), SidePreference.RANDOM, "guest$i", "닉$i", "2.2.2.2")
        }
        assertThatThrownBy {
            service.createRoom("chess", TimeRule.untimed(), SidePreference.RANDOM, "newguest", "닉", "2.2.2.2")
        }.isInstanceOf(RoomLimitException::class.java)
    }

    @Test
    fun `한도 - 초대코드 확인은 IP당 분당 10번`() {
        repeat(10) { service.checkCodeAttempt("3.3.3.3") }
        assertThatThrownBy { service.checkCodeAttempt("3.3.3.3") }
            .isInstanceOf(RoomLimitException::class.java)
        // 다른 IP 는 영향 없음
        service.checkCodeAttempt("4.4.4.4")
        // 1분이 지나면 다시 가능
        clock.advance(61_000)
        service.checkCodeAttempt("3.3.3.3")
    }

    @Test
    fun `방을 닫으면 한도 카운트가 돌아온다`() {
        val rooms = (0 until 3).map { i ->
            service.createRoom("chess", TimeRule.untimed(), SidePreference.RANDOM, "g1", "닉$i", "1.1.1.1")
        }
        assertThatThrownBy {
            service.createRoom("chess", TimeRule.untimed(), SidePreference.RANDOM, "g1", "닉", "1.1.1.1")
        }.isInstanceOf(RoomLimitException::class.java)
        service.closeRoom(rooms.first().code)
        service.createRoom("chess", TimeRule.untimed(), SidePreference.RANDOM, "g1", "닉", "1.1.1.1")
    }

    @Test
    fun `관전자는 방당 최대 10명이다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        repeat(10) { i ->
            val join = service.join(room.code, "spec$i", "관전$i")
            assertThat(join.role).isEqualTo(Role.SPECTATOR)
            assertThat(join.allowed).isTrue()
        }
        val join = service.join(room.code, "spec11", "넘침")
        assertThat(join.allowed).isFalse()
    }

    @Test
    fun `관전자 재참가는 같은 자리다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        service.join(room.code, "spec1", "관전1")
        service.disconnect(room, "spec1")
        assertThat(room.spectatorsSnapshot().map { it.guestId }).doesNotContain("spec1")
        val again = service.join(room.code, "spec1", "관전1")
        assertThat(again.role).isEqualTo(Role.SPECTATOR)
        assertThat(room.spectatorsSnapshot().map { it.guestId }).contains("spec1")
    }

    @Test
    fun `대국자와 관전자 모두 채팅할 수 있고 표시가 갈린다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        service.join(room.code, "spec1", "관전1")
        val p = service.chat(room, "host", "안녕")
        assertThat(p).isNotNull
        assertThat(p!!.fromPlayer).isTrue()
        val sp = service.chat(room, "spec1", "관전입니다")
        assertThat(sp).isNotNull
        assertThat(sp!!.fromPlayer).isFalse()
    }

    @Test
    fun `채팅은 1인당 1초에 1개까지`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        assertThat(service.chat(room, "host", "첫 번째")).isNotNull
        assertThat(service.chat(room, "host", "두 번째")).isNull()
        assertThat(service.chat(room, "guest2", "다른 사람은 가능")).isNotNull
        clock.advance(1_000)
        assertThat(service.chat(room, "host", "1초 뒤")).isNotNull
    }

    @Test
    fun `채팅은 200자까지이고 방에 없는 사람은 못 쓴다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        assertThat(service.chat(room, "host", "가".repeat(200))).isNotNull
        clock.advance(1_100)
        assertThat(service.chat(room, "host", "가".repeat(201))!!.text).hasSize(200)
        clock.advance(1_100)
        assertThat(service.chat(room, "stranger", "외부인")).isNull()
        assertThat(service.chat(room, "host", "   ")).isNull()
    }

    @Test
    fun `입장과 퇴장이 채팅에 알림으로 남는다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        service.join(room.code, "spec1", "관전1")
        service.disconnect(room, "spec1")
        val texts = room.chatSnapshot().map { it.text }
        assertThat(texts).contains("관전1 입장", "관전1 퇴장")
        assertThat(room.chatSnapshot().filter { !it.system }.size).isEqualTo(0)
    }

    @Test
    fun `채팅 기록은 최근 50개만 남는다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        for (i in 1..60) {
            clock.advance(1_100)
            service.chat(room, "host", "msg$i")
        }
        assertThat(room.chatSnapshot().filter { !it.system }).hasSize(50)
        assertThat(room.chatSnapshot().last().text).isEqualTo("msg60")
    }

    @Test
    fun `재대국은 양쪽 모두 동의해야 시작된다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        service.resign(room, "host")
        // 한쪽만 누르면 시작 안 함
        assertThat(service.requestRematch(room, "host")).isFalse()
        assertThat(room.phase).isEqualTo(RoomPhase.FINISHED)
        // 상대도 누르면 시작
        assertThat(service.requestRematch(room, "guest2")).isTrue()
        assertThat(room.phase).isEqualTo(RoomPhase.PLAYING)
        assertThat(room.movesSnapshot()).isEmpty()
        assertThat(room.result).isNull()
    }

    @Test
    fun `재대국하면 진영이 서로 바뀐다`() {
        val room = makeRoom() // host = WHITE
        service.join(room.code, "guest2", "상대") // BLACK
        val before = listOf(room.sideOf("host"), room.sideOf("guest2"))
        service.resign(room, "guest2")
        service.requestRematch(room, "host")
        service.requestRematch(room, "guest2")
        val after = listOf(room.sideOf("host"), room.sideOf("guest2"))
        assertThat(after).containsExactly(Side.BLACK, Side.WHITE)
        assertThat(before).containsExactly(Side.WHITE, Side.BLACK)
    }

    @Test
    fun `재대국 시작 시 시계와 방 수명이 갱신된다`() {
        val room = makeRoom(rule = TimeRule(60_000, 0, 0))
        service.join(room.code, "guest2", "상대")
        clock.advance(20 * 60_000)
        service.resign(room, "host")
        clock.advance(9 * 60_000) // 종료 후 9분 → 아직 정리 아님
        assertThat(room.expired(clock.now)).isFalse()
        service.requestRematch(room, "host")
        service.requestRematch(room, "guest2")
        // 재대국이 시작되면 수명 타이머가 처음부터 다시 돈다: 새로 끝나고 9분이면 유지
        service.resign(room, "host")
        clock.advance(9 * 60_000)
        assertThat(room.expired(clock.now)).isFalse()
        clock.advance(2 * 60_000)
        assertThat(room.expired(clock.now)).isTrue()
        // 시계도 새로 채워졌다
        assertThat(room.clockSnapshot().getValue(Side.WHITE).baseLeftMs).isEqualTo(60_000)
    }

    @Test
    fun `대국자가 나가면 재대국을 할 수 없다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        service.resign(room, "host")
        service.leave(room, "guest2")
        assertThat(room.playerOf("guest2")).isNull()
        assertThat(service.requestRematch(room, "host")).isFalse()
        assertThat(room.phase).isEqualTo(RoomPhase.FINISHED)
    }

    @Test
    fun `재대국 직전 방장만 시간 규칙을 바꿀 수 있다`() {
        val room = makeRoom()
        service.join(room.code, "guest2", "상대")
        service.resign(room, "host")
        val newRule = TimeRule(5 * 60_000, 10_000, 3)
        assertThat(service.updateTimeRule(room, "guest2", newRule)).isFalse()
        assertThat(service.updateTimeRule(room, "host", newRule)).isTrue()
        service.requestRematch(room, "host")
        service.requestRematch(room, "guest2")
        assertThat(room.clockSnapshot().getValue(Side.WHITE).baseLeftMs)
            .isEqualTo(5 * 60_000)
        assertThat(room.clockSnapshot().getValue(Side.WHITE).byoYomiLeft).isEqualTo(3)
    }

    @Test
    fun `상차림 방 - 두 대국자 모두 제출하면 시작된다`() {
        val room = service.createRoom(
            "janggi",
            TimeRule(300_000, 30_000, 3),
            SidePreference.WHITE,
            "h",
            "초",
            "1.1.1.1",
            requiresSetup = true,
        )
        assertThat(room.phase).isEqualTo(RoomPhase.WAITING)
        service.join(room.code, "g2", "한")
        assertThat(room.phase).isEqualTo(RoomPhase.SETUP)
        // 시작 전에는 수를 둘 수 없다
        assertThat(service.addMove(room, "h", "9,1", "7,2", "마")).isNull()
        assertThat(service.submitSetup(room, "h", "마상마상")).isTrue()
        assertThat(room.phase).isEqualTo(RoomPhase.SETUP)
        assertThat(service.submitSetup(room, "g2", "상마상마")).isTrue()
        assertThat(room.phase).isEqualTo(RoomPhase.PLAYING)
        assertThat(room.setupSnapshot()[Side.WHITE]).isEqualTo("마상마상")
        assertThat(room.setupSnapshot()[Side.BLACK]).isEqualTo("상마상마")
    }

    @Test
    fun `상차림 방 재대국 - 진영 교대 후 상차림을 다시 고른다`() {
        val room = service.createRoom(
            "janggi",
            TimeRule(300_000, 30_000, 3),
            SidePreference.WHITE,
            "h",
            "초",
            "1.1.1.1",
            requiresSetup = true,
        )
        service.join(room.code, "g2", "한")
        service.submitSetup(room, "h", "마상마상")
        service.submitSetup(room, "g2", "상마상마")
        service.resign(room, "h")
        service.requestRematch(room, "h")
        service.requestRematch(room, "g2")
        // 재대국 합의 → SETUP 단계, 진영은 교대
        assertThat(room.phase).isEqualTo(RoomPhase.SETUP)
        assertThat(room.sideOf("h")).isEqualTo(Side.BLACK)
        assertThat(room.setupSnapshot()).isEmpty()
        service.submitSetup(room, "h", "마상상마")
        service.submitSetup(room, "g2", "상마마상")
        assertThat(room.phase).isEqualTo(RoomPhase.PLAYING)
        assertThat(room.setupSnapshot()[Side.WHITE]).isEqualTo("상마마상")
    }

    @Test
    fun `상차림 없는 방은 두 명이 모이면 바로 시작된다`() {
        val room = makeRoom()
        service.join(room.code, "g2", "상대")
        assertThat(room.phase).isEqualTo(RoomPhase.PLAYING)
        assertThat(service.submitSetup(room, "host", "마상마상")).isFalse()
    }
}
