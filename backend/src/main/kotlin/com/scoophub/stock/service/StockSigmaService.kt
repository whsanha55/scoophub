package com.scoophub.stock.service

import com.scoophub.stock.SigmaBand
import com.scoophub.stock.SigmaStateEnum
import com.scoophub.stock.StockSigmaBand
import com.scoophub.stock.dto.SigmaBandOut
import com.scoophub.stock.dto.SigmaWeekOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockSigmaEntity
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.vo.Candle
import org.springframework.stereotype.Service
import java.time.LocalDate

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
        val bandsByTicker = loadBands(targets.map { it.ticker }, minDate)
        return targets.mapNotNull { row ->
            val date = row.candleDate ?: return@mapNotNull null
            val band = StockSigmaBand.bandOn(bandsByTicker[row.ticker].orEmpty(), date) ?: return@mapNotNull null
            row.ticker to SigmaBandOut.of(band, row.price, date)
        }.toMap()
    }

    /**
     * 분석 행의 마지막 일봉 바로 전 거래일 종가의 σ 상태 (#267 신규·지속 구분).
     * 전 거래일이 앵커일이면 그 날을 덮는 지난주 밴드로 본다. 일봉이나 적용 밴드가 없는 티커는 빠진다.
     */
    fun findPreviousStates(rows: List<StockAnalysisResultEntity>): Map<String, SigmaStateEnum> {
        val targets = rows.filter { it.candleDate != null }
        val dates = targets.mapNotNull { it.candleDate }
        val minDate = dates.minOrNull() ?: return emptyMap()
        val tickers = targets.map { it.ticker }
        val candlesByTicker = candleRepository
            .findByTickerInAndIntervalAndDateBetween(
                tickers,
                "1D",
                minDate.minusDays(PREVIOUS_LOOKBACK_DAYS),
                dates.max(),
            )
            .groupBy { it.ticker }
        val bandsByTicker = loadBands(tickers, minDate.minusDays(PREVIOUS_LOOKBACK_DAYS))
        return targets.mapNotNull { row ->
            val date = row.candleDate ?: return@mapNotNull null
            val previous = candlesByTicker[row.ticker].orEmpty()
                .filter { it.date.isBefore(date) }
                .maxByOrNull { it.date } ?: return@mapNotNull null
            val band = StockSigmaBand.bandOn(bandsByTicker[row.ticker].orEmpty(), previous.date)
                ?: return@mapNotNull null
            row.ticker to StockSigmaBand.state(band.position(previous.close))
        }.toMap()
    }

    private fun loadBands(tickers: List<String>, from: LocalDate): Map<String, List<SigmaBand>> = sigmaRepository
        .findByTickerInAndSnapshotDateGreaterThanEqual(tickers, from.minusDays(ANCHOR_LOOKBACK_DAYS))
        .groupBy { it.ticker }
        .mapValues { (_, snapshots) -> StockSigmaBand.weeklyBands(snapshots.map { toBand(it) }) }

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

        /** 연휴가 길어도 전 거래일 일봉이 들어오도록 넉넉히 본다 */
        const val PREVIOUS_LOOKBACK_DAYS = 10L

        fun toBand(row: StockSigmaEntity) = SigmaBand(
            snapshotDate = row.snapshotDate,
            expiryDate = row.expiryDate,
            anchorPrice = row.currentPrice,
            expectedMove = row.expectedMove,
        )
    }
}
