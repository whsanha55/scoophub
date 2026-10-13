package com.scoophub.stock.vo

import java.time.Instant
import java.time.LocalDate

/** GEX 계산 입력 — 감마와 open interest 가 모두 있는 옵션 계약 1건 */
data class GexContract(val strike: Double, val isCall: Boolean, val gamma: Double, val openInterest: Long)

/** 종목의 GEX 입력 계약 묶음. oiDate 는 계약들 중 가장 최근 OI 기준일 */
data class GexChain(val oiDate: LocalDate?, val contracts: List<GexContract>)

/** 행사가별 딜러 감마 노출. 단위는 현재가 1% 변동당 달러 */
data class GexStrike(val strike: Double, val callGex: Double, val putGex: Double, val netGex: Double)

/** stock_gex 조회 프로젝션 */
data class StockGexRow(
    val ticker: String,
    val snapshotDate: LocalDate,
    val snapshotAt: Instant,
    val oiDate: LocalDate?,
    val spot: Double,
    val netGex: Double,
    val gammaFlip: Double?,
    val resistances: List<Double>,
    val supports: List<Double>,
    val accelerations: List<Double>,
    val strikes: List<GexStrike>,
)
