package com.scoophub.stock

import com.scoophub.stock.vo.GexContract
import com.scoophub.stock.vo.GexStrike
import kotlin.math.abs
import kotlin.math.sign

/** 행사가별 GEX 계산 결과. 레벨 목록은 GEX 크기순 */
data class GexResult(
    val spot: Double,
    val netGex: Double,
    val gammaFlip: Double?,
    val resistances: List<Double>,
    val supports: List<Double>,
    val accelerations: List<Double>,
    val strikes: List<GexStrike>,
)

/**
 * 행사가별 딜러 감마 노출(GEX) — 딜러가 콜은 롱, 풋은 숏이라고 가정한다(콜 +, 풋 −).
 * 계약 GEX = OI × gamma × 100(계약 승수) × spot² × 0.01 → 현재가 1% 변동 시 딜러 헤지 금액(달러).
 */
object StockGex {
    /** 현재가 ±10% 행사가만 본다. 멀리 떨어진 행사가는 감마가 작고 사다리만 길어진다 */
    const val STRIKE_RANGE = 0.10
    private const val CONTRACT_MULTIPLIER = 100.0
    private const val ONE_PERCENT = 0.01
    private const val LEVEL_COUNT = 2

    fun compute(contracts: List<GexContract>, spot: Double): GexResult? {
        val low = spot * (1 - STRIKE_RANGE)
        val high = spot * (1 + STRIKE_RANGE)
        val strikes = contracts
            .filter { it.strike in low..high && it.openInterest > 0 && it.gamma > 0 }
            .groupBy { it.strike }
            .toSortedMap()
            .map { (strike, list) ->
                val callGex = list.filter { it.isCall }.sumOf { contractGex(it, spot) }
                val putGex = -list.filterNot { it.isCall }.sumOf { contractGex(it, spot) }
                GexStrike(strike = strike, callGex = callGex, putGex = putGex, netGex = callGex + putGex)
            }
        if (strikes.isEmpty()) {
            return null
        }
        return GexResult(
            spot = spot,
            netGex = strikes.sumOf { it.netGex },
            gammaFlip = gammaFlip(strikes, spot),
            resistances = topLevels(strikes.filter { it.strike > spot && it.netGex > 0 }),
            supports = topLevels(strikes.filter { it.strike < spot && it.netGex > 0 }),
            accelerations = topLevels(strikes.filter { it.netGex < 0 }),
            strikes = strikes,
        )
    }

    private fun contractGex(c: GexContract, spot: Double): Double =
        c.openInterest * c.gamma * CONTRACT_MULTIPLIER * spot * spot * ONE_PERCENT

    private fun topLevels(strikes: List<GexStrike>): List<Double> = strikes
        .sortedByDescending { abs(it.netGex) }
        .take(LEVEL_COUNT)
        .map { it.strike }

    /**
     * 낮은 행사가부터 누적한 net GEX 의 부호가 바뀌는 지점(인접 행사가 사이 선형 보간).
     * 여러 번 바뀌면 현재가에 가장 가까운 지점. 가상의 현재가마다 감마를 다시 계산하는 정식 방식 대신 쓰는 근사다.
     */
    private fun gammaFlip(strikes: List<GexStrike>, spot: Double): Double? {
        val cumulative = strikes.runningFold(0.0) { acc, s -> acc + s.netGex }.drop(1)
        return (1 until strikes.size)
            .filter { i -> cumulative[i - 1] != 0.0 && sign(cumulative[i - 1]) != sign(cumulative[i]) }
            .map { i ->
                val (k0, k1) = strikes[i - 1].strike to strikes[i].strike
                val (c0, c1) = cumulative[i - 1] to cumulative[i]
                k0 + (k1 - k0) * (-c0 / (c1 - c0))
            }
            .minByOrNull { abs(it - spot) }
    }
}
