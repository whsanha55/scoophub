package com.scoophub.stock.vo

import java.time.LocalDate

enum class SigmaPosition { ABOVE_1SIGMA, WITHIN_UPPER, NEAR_CENTER, WITHIN_LOWER, BELOW_1SIGMA }

enum class SigmaSignalType { STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL }

data class SigmaRange(
    val ticker: String = "",
    val weekStart: LocalDate? = null,
    val weekEnd: LocalDate? = null,
    val center: Double = 0.0,
    val upper1sigma: Double = 0.0,
    val lower1sigma: Double = 0.0,
    val upper2sigma: Double = 0.0,
    val lower2sigma: Double = 0.0,
    val currentPrice: Double = 0.0,
    val sigmaPosition: SigmaPosition = SigmaPosition.NEAR_CENTER,
)

data class SigmaSignal(
    val ticker: String = "",
    val date: LocalDate? = null,
    val sigmaPosition: SigmaPosition = SigmaPosition.NEAR_CENTER,
    val signal: SigmaSignalType = SigmaSignalType.NEUTRAL,
    val confidence: Double = 0.0,
    val price: Double = 0.0,
    val sigmaRange: SigmaRange? = null,
)

/** legacy `stock/models.py` WeeklyExpectedMove — 주간 예상움직임 값 객체 */
data class WeeklyExpectedMove(
    val id: Int? = null,
    val ticker: String,
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val expectedMoveHigh: Double,
    val expectedMoveLow: Double,
    val expectedMovePct: Double,
)

object SigmaModel {
    /** WEM 으로부터 sigma 구간 계산 — high/low 를 ±1σ 로 제공받는다 */
    fun computeSigmaRange(wem: WeeklyExpectedMove, currentPrice: Double): SigmaRange {
        val center = (wem.expectedMoveHigh + wem.expectedMoveLow) / 2
        // 예상 변동폭의 절반이 곧 1σ
        val sigma = (wem.expectedMoveHigh - wem.expectedMoveLow) / 2

        val upper1 = center + sigma
        val lower1 = center - sigma

        // 현재 가격의 sigma 구간 내 위치 (5단계)
        val position = when {
            currentPrice >= upper1 -> SigmaPosition.ABOVE_1SIGMA
            currentPrice >= center + sigma * 0.5 -> SigmaPosition.WITHIN_UPPER
            currentPrice >= center - sigma * 0.5 -> SigmaPosition.NEAR_CENTER
            currentPrice >= lower1 -> SigmaPosition.WITHIN_LOWER
            else -> SigmaPosition.BELOW_1SIGMA
        }
        return SigmaRange(
            ticker = wem.ticker,
            weekStart = wem.weekStart,
            weekEnd = wem.weekEnd,
            center = center,
            upper1sigma = upper1,
            lower1sigma = lower1,
            upper2sigma = center + 2 * sigma,
            lower2sigma = center - 2 * sigma,
            currentPrice = currentPrice,
            sigmaPosition = position,
        )
    }

    /** sigma 위치 기반 역추세(contrarian) 시그널 — 하방 극단 매수, 상방 극단 매도 */
    fun generateSigmaSignal(range: SigmaRange): SigmaSignal {
        val (signal, confidence) = when (range.sigmaPosition) {
            SigmaPosition.BELOW_1SIGMA -> SigmaSignalType.STRONG_BUY to 0.8
            SigmaPosition.WITHIN_LOWER -> SigmaSignalType.BUY to 0.6
            SigmaPosition.NEAR_CENTER -> SigmaSignalType.NEUTRAL to 0.3
            SigmaPosition.WITHIN_UPPER -> SigmaSignalType.SELL to 0.6
            SigmaPosition.ABOVE_1SIGMA -> SigmaSignalType.STRONG_SELL to 0.8
        }
        return SigmaSignal(
            ticker = range.ticker,
            date = range.weekStart,
            sigmaPosition = range.sigmaPosition,
            signal = signal,
            confidence = confidence,
            price = range.currentPrice,
            sigmaRange = range,
        )
    }
}
