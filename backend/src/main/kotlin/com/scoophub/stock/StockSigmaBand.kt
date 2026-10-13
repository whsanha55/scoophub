package com.scoophub.stock

import java.time.LocalDate

enum class SigmaStateEnum { NORMAL, UPPER_BREAK, LOWER_BREAK }

/**
 * 주간 σ 밴드 — 앵커 스냅샷의 종가(anchorPrice) ± expectedMove(1σ).
 * 스냅샷은 장 마감 후에 찍혀 anchorPrice 가 snapshotDate 의 종가이므로, 밴드는 그 다음 거래일부터 만기일(주간 마감)까지 적용한다.
 */
data class SigmaBand(
    val snapshotDate: LocalDate,
    val expiryDate: LocalDate,
    val anchorPrice: Double,
    val expectedMove: Double,
) {
    val upper: Double get() = anchorPrice + expectedMove
    val lower: Double get() = anchorPrice - expectedMove

    fun covers(date: LocalDate): Boolean = date.isAfter(snapshotDate) && !date.isAfter(expiryDate)

    fun position(close: Double): Double = (close - anchorPrice) / expectedMove
}

/** 주간 σ 밴드 위치 계산. 판단 신호(매수·매도)는 만들지 않는다 (#251) */
object StockSigmaBand {
    private const val BREAK = 1.0

    /**
     * 날짜별 스냅샷에서 주간 앵커만 고른다.
     * 스냅샷은 매 거래일 찍히지만 그날 종가가 앵커면 σ 위치가 항상 0 이라, 같은 만기를 처음 연 스냅샷(보통 직전 주 금요일 마감)을 그 주 앵커로 쓴다.
     * 한 날짜에 만기가 여럿이면(#250 이전 데이터) 가장 가까운 만기만 쓴다.
     */
    fun weeklyBands(snapshots: List<SigmaBand>): List<SigmaBand> = snapshots
        .filter { it.expectedMove > 0 && it.anchorPrice > 0 }
        .groupBy { it.snapshotDate }
        .map { (_, sameDay) -> sameDay.minBy { it.expiryDate } }
        .groupBy { it.expiryDate }
        .map { (_, sameExpiry) -> sameExpiry.minBy { it.snapshotDate } }
        .sortedBy { it.snapshotDate }

    /** date 에 적용되는 밴드. 겹치면 더 최근 앵커 */
    fun bandOn(bands: List<SigmaBand>, date: LocalDate): SigmaBand? =
        bands.filter { it.covers(date) }.maxByOrNull { it.snapshotDate }

    fun state(position: Double): SigmaStateEnum = when {
        position >= BREAK -> SigmaStateEnum.UPPER_BREAK
        position <= -BREAK -> SigmaStateEnum.LOWER_BREAK
        else -> SigmaStateEnum.NORMAL
    }
}
