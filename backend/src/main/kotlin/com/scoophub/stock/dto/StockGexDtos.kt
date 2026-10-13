package com.scoophub.stock.dto

import com.scoophub.stock.vo.StockGexRow
import java.time.Instant

/** 행사가별 GEX 최신 스냅샷. GEX 단위는 현재가 1% 변동당 달러, net_gex 가 음수면 딜러 숏감마 */
data class GexOut(
    val ticker: String,
    val snapshotDate: String,
    val snapshotAt: Instant,
    val oiDate: String?,
    val spot: Double,
    val netGex: Double,
    val gammaFlip: Double?,
    val resistances: List<Double>,
    val supports: List<Double>,
    val accelerations: List<Double>,
    val strikes: List<GexStrikeOut>,
) {
    companion object {
        fun from(row: StockGexRow) = GexOut(
            ticker = row.ticker,
            snapshotDate = row.snapshotDate.toString(),
            snapshotAt = row.snapshotAt,
            oiDate = row.oiDate?.toString(),
            spot = row.spot,
            netGex = row.netGex,
            gammaFlip = row.gammaFlip,
            resistances = row.resistances,
            supports = row.supports,
            accelerations = row.accelerations,
            strikes = row.strikes.map {
                GexStrikeOut(strike = it.strike, callGex = it.callGex, putGex = it.putGex, netGex = it.netGex)
            },
        )
    }
}

data class GexStrikeOut(val strike: Double, val callGex: Double, val putGex: Double, val netGex: Double)
