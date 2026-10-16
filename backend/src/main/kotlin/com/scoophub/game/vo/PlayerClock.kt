package com.scoophub.game.vo

data class PlayerClock(
    val baseLeftMs: Long,
    val isInByoYomi: Boolean,
    val byoYomiLeftMs: Long,
    val byoYomiLeft: Int,
    val isFlagged: Boolean = false,
) {
    /** 시간이 elapsedMs 만큼 흘렀을 때 갱신한다. */
    fun tick(rule: TimeRule, elapsedMs: Long): PlayerClock {
        if (rule.isUntimed) return this
        if (!isInByoYomi) {
            val left = baseLeftMs - elapsedMs
            if (left > 0) return copy(baseLeftMs = left)
            // 기본 시간 소진 → 초읽기 진입. 잔여는 버리고 첫 수 전체 시간을 준다.
            if (rule.byoYomiMs > 0) {
                return copy(baseLeftMs = 0, isInByoYomi = true, byoYomiLeftMs = rule.byoYomiMs)
            }
            return copy(baseLeftMs = 0, isFlagged = true)
        }
        val left = byoYomiLeftMs - elapsedMs
        if (left > 0) return copy(byoYomiLeftMs = left)
        if (byoYomiLeft > 0) {
            return copy(byoYomiLeft = byoYomiLeft - 1, byoYomiLeftMs = rule.byoYomiMs)
        }
        return copy(byoYomiLeftMs = 0, isFlagged = true)
    }

    /** 수를 둬서 시계가 멈췄을 때 갱신한다. 초읽기에서는 다음 수 시간을 다시 채운다. */
    fun afterMove(rule: TimeRule): PlayerClock = if (isInByoYomi) copy(byoYomiLeftMs = rule.byoYomiMs) else this
}
