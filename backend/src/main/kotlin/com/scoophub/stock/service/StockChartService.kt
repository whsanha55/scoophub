package com.scoophub.stock.service

import com.scoophub.stock.StockChart
import com.scoophub.stock.StockSigmaBand
import com.scoophub.stock.dto.StockChartOut
import com.scoophub.stock.dto.StockChartPointOut
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.vo.Candle
import org.springframework.stereotype.Service

/** 상세 차트 (#261) — DB 일봉과 stock_sigma 스냅샷에서 계산 */
@Service
class StockChartService(
    private val candleRepository: StockCandleRepository,
    private val sigmaRepository: StockSigmaRepository,
) {
    /** 최근 1년. SMA200 이 첫 날부터 보이도록 계산은 전체 일봉으로 한다 */
    fun findChart(ticker: String): StockChartOut {
        val candles = candleRepository.findByTickerAndIntervalOrderByDate(ticker, "1D").map {
            Candle(it.ticker, it.interval, it.date, it.open, it.high, it.low, it.close, it.volume)
        }
        val last = candles.lastOrNull()?.date ?: return StockChartOut(ticker, emptyList())
        val from = last.minusYears(1)
        val bands = StockSigmaBand.weeklyBands(
            sigmaRepository.findByTickerInAndSnapshotDateGreaterThanEqual(
                listOf(ticker),
                from.minusDays(StockSigmaService.ANCHOR_LOOKBACK_DAYS),
            ).map { StockSigmaService.toBand(it) },
        )
        val points = StockChart.points(candles, bands)
            .filter { !it.date.isBefore(from) }
            .map { StockChartPointOut.from(it) }
        return StockChartOut(ticker, points)
    }
}
