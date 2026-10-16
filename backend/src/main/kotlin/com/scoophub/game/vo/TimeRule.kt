package com.scoophub.game.vo

/**
 * 보드게임 공통 시간 규칙(기본 시간 + 초읽기)의 서버 계산.
 * 프론트엔드 time-control.ts 와 같은 규칙을 쓴다 (ADR-0001: 초대 대국 시계는 서버가 판정).
 *
 * 기본 시간이 소진하면 초읽기로 넘어간다. 초읽기에서 한 수마다 짧은 시간이 주어지고,
 * 시간을 넘기면 정해진 횟수에서 하나를 소비하며, 다 소진한 뒤 넘기면 시간패다.
 */
data class TimeRule(val baseMs: Long, val byoYomiMs: Long, val byoYomiCount: Int) {
    val isUntimed: Boolean get() = baseMs == 0L && byoYomiMs == 0L

    fun initialClock(): PlayerClock = PlayerClock(
        baseLeftMs = baseMs,
        isInByoYomi = baseMs == 0L && byoYomiMs > 0,
        byoYomiLeftMs = byoYomiMs,
        byoYomiLeft = byoYomiCount,
    )

    companion object {
        const val MAX_BASE_MS = 60L * 60_000
        const val MAX_BYOYOMI_MS = 60_000L
        const val MAX_BYOYOMI_COUNT = 5

        fun untimed(): TimeRule = TimeRule(0, 0, 0)

        /** 클라이언트가 보낸 값을 상한 안으로 맞춘다. 초읽기 시간이 없으면 횟수도 0이다. */
        fun of(baseMs: Long, byoYomiMs: Long, byoYomiCount: Int): TimeRule {
            val byo = byoYomiMs.coerceIn(0, MAX_BYOYOMI_MS)
            return TimeRule(
                baseMs = baseMs.coerceIn(0, MAX_BASE_MS),
                byoYomiMs = byo,
                byoYomiCount = if (byo == 0L) 0 else byoYomiCount.coerceIn(0, MAX_BYOYOMI_COUNT),
            )
        }
    }
}
