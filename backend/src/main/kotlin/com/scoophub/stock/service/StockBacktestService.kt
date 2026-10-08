package com.scoophub.stock.service

import com.scoophub.stock.BacktestResult
import com.scoophub.stock.StockBacktest
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.vo.Candle
import org.springframework.stereotype.Service

@Service
class StockBacktestService(
    private val watchlistRepository: StockWatchlistRepository,
    private val candleRepository: StockCandleRepository,
) {
    /** 활성 관심종목의 DB 일봉 전체로 실행. 평가 가능한 종목이 없으면 null */
    fun run(costPerSide: Double): BacktestResult? {
        val candlesByTicker = watchlistRepository.findByIsActiveOrderByAddedAt().associate { item ->
            item.ticker to candleRepository.findByTickerAndIntervalOrderByDate(item.ticker, "1D").map {
                Candle(it.ticker, it.interval, it.date, it.open, it.high, it.low, it.close, it.volume)
            }
        }
        return StockBacktest.run(candlesByTicker, costPerSide)
    }
}
