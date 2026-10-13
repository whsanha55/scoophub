package com.scoophub.stock.service

import com.scoophub.stock.SigmaBand
import com.scoophub.stock.StockSigmaBand
import com.scoophub.stock.dto.SigmaBandOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockSigmaEntity
import com.scoophub.stock.repository.StockSigmaRepository
import org.springframework.stereotype.Service

/** 주간 σ 밴드 조회 — stock_sigma 스냅샷에서 계산하며 저장하지 않는다 (#260) */
@Service
class StockSigmaService(private val sigmaRepository: StockSigmaRepository) {
    /** 분석 행의 마지막 일봉(candleDate)·종가(price) 기준 σ 위치. 적용 밴드가 없는 티커는 빠진다 */
    fun findCurrentBands(rows: List<StockAnalysisResultEntity>): Map<String, SigmaBandOut> {
        val targets = rows.filter { it.candleDate != null && it.price > 0 }
        val minDate = targets.mapNotNull { it.candleDate }.minOrNull() ?: return emptyMap()
        val bandsByTicker = sigmaRepository
            .findByTickerInAndSnapshotDateGreaterThanEqual(
                targets.map { it.ticker },
                minDate.minusDays(ANCHOR_LOOKBACK_DAYS),
            )
            .groupBy { it.ticker }
            .mapValues { (_, snapshots) -> StockSigmaBand.weeklyBands(snapshots.map { toBand(it) }) }
        return targets.mapNotNull { row ->
            val date = row.candleDate ?: return@mapNotNull null
            val band = StockSigmaBand.bandOn(bandsByTicker[row.ticker].orEmpty(), date) ?: return@mapNotNull null
            row.ticker to SigmaBandOut.of(band, row.price, date)
        }.toMap()
    }

    companion object {
        /** 주간 앵커는 만기 직전 주에 찍히므로 기준일보다 3주 앞까지만 보면 충분하다 */
        const val ANCHOR_LOOKBACK_DAYS = 21L

        fun toBand(row: StockSigmaEntity) = SigmaBand(
            snapshotDate = row.snapshotDate,
            expiryDate = row.expiryDate,
            anchorPrice = row.currentPrice,
            expectedMove = row.expectedMove,
        )
    }
}
