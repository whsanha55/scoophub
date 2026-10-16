package com.scoophub.game.domain

/**
 * 보드게임 공통 시간 규칙(기본 시간 + 초읽기)의 서버 계산.
 * 프론트엔드 time-control.ts 와 같은 규칙을 쓴다 (ADR-0001: 초대 대국 시계는 서버가 판정).
 *
 * 기본 시간이 소진하면 초읽기로 넘어간다. 초읽기에서 한 수마다 짧은 시간이 주어지고,
 * 시간을 넘기면 정해진 횟수에서 하나를 소비하며, 다 소진한 뒤 넘기면 시간패다.
 */
data class TimeRule(val baseMs: Long, val byoYomiMs: Long, val byoYomiCount: Int) {
    val untimed: Boolean get() = baseMs == 0L && byoYomiMs == 0L

    companion object {
        fun untimed() = TimeRule(0, 0, 0)
    }
}

data class PlayerClock(
    val baseLeftMs: Long,
    val inByoYomi: Boolean,
    val byoYomiLeftMs: Long,
    val byoYomiLeft: Int,
    val flagged: Boolean = false,
)

fun initClock(rule: TimeRule): PlayerClock {
    val startInByoYomi = rule.baseMs == 0L && rule.byoYomiMs > 0
    return PlayerClock(
        baseLeftMs = rule.baseMs,
        inByoYomi = startInByoYomi,
        byoYomiLeftMs = rule.byoYomiMs,
        byoYomiLeft = rule.byoYomiCount,
    )
}

/** 시간이 elapsedMs 만큼 흘렀을 때 갱신한다. */
fun tickClock(clock: PlayerClock, rule: TimeRule, elapsedMs: Long): PlayerClock {
    if (rule.untimed) return clock
    if (!clock.inByoYomi) {
        val left = clock.baseLeftMs - elapsedMs
        if (left > 0) return clock.copy(baseLeftMs = left)
        // 기본 시간 소진 → 초읽기 진입. 잔여는 버리고 첫 수 전체 시간을 준다.
        if (rule.byoYomiMs > 0) {
            return clock.copy(baseLeftMs = 0, inByoYomi = true, byoYomiLeftMs = rule.byoYomiMs)
        }
        return clock.copy(baseLeftMs = 0, flagged = true)
    }
    val left = clock.byoYomiLeftMs - elapsedMs
    if (left > 0) return clock.copy(byoYomiLeftMs = left)
    if (clock.byoYomiLeft > 0) {
        return clock.copy(byoYomiLeft = clock.byoYomiLeft - 1, byoYomiLeftMs = rule.byoYomiMs)
    }
    return clock.copy(byoYomiLeftMs = 0, flagged = true)
}

/** 수를 둬서 시계가 멈췄을 때 갱신한다. 초읽기에서는 다음 수 시간을 다시 채운다. */
fun clockAfterMove(clock: PlayerClock, rule: TimeRule): PlayerClock {
    if (!clock.inByoYomi) return clock
    return clock.copy(byoYomiLeftMs = rule.byoYomiMs)
}
