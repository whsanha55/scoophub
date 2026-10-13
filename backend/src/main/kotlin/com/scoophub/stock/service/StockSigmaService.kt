package com.scoophub.stock.service

import com.scoophub.stock.SigmaBand
import com.scoophub.stock.StockSigmaBand
import com.scoophub.stock.dto.SigmaBandOut
import com.scoophub.stock.dto.SigmaWeekOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockSigmaEntity
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.vo.Candle
import org.springframework.stereotype.Service

/** 주간 σ 밴드 조회 — stock_sigma 스냅샷과 DB 일봉에서 계산하며 저장하지 않는다 (#260, #262) */
@Service
class StockSigmaService(
    private val sigmaRepository: StockSigmaRepository,
    private val candleRepository: StockCandleRepository,
) {
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

    /** 주간 앵커마다 마감 σ 위치와 이후 +1W/+2W/+4W 수익률 (#262). 최근 주가 먼저 */
    fun findWeeklyHistory(ticker: String): List<SigmaWeekOut> {
        val candles = candleRepository.findByTickerAndIntervalOrderByDate(ticker, "1D").map {
            Candle(it.ticker, it.interval, it.date, it.open, it.high, it.low, it.close, it.volume)
        }
        val first = candles.firstOrNull()?.date ?: return emptyList()
        val bands = StockSigmaBand.weeklyBands(
            sigmaRepository.findByTickerInAndSnapshotDateGreaterThanEqual(
                listOf(ticker),
                first.minusDays(ANCHOR_LOOKBACK_DAYS),
            ).map { toBand(it) },
        )
        return StockSigmaBand.weeklyHistory(bands, candles).map { SigmaWeekOut.from(it) }
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
