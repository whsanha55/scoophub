package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class BacktestStats(
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val exposure: Double,
    val tradesPerYear: Double,
)

data class TickerBacktest(
    val ticker: String,
    val start: LocalDate,
    val end: LocalDate,
    val strategy: BacktestStats,
    val buyAndHold: BacktestStats,
)

/** strategy/buyAndHold 는 종목 동일가중 포트폴리오. 날짜마다 그날 거래 중인 종목만 평균한다 */
data class BacktestResult(
    val rule: String,
    val costPerSide: Double,
    val start: LocalDate,
    val end: LocalDate,
    val strategy: BacktestStats,
    val buyAndHold: BacktestStats,
    val tickers: List<TickerBacktest>,
)

/**
 * 골든크로스 보유 규칙 vs 그냥 보유 백테스트.
 * 리포트가 장 마감 뒤 나가므로 t 종가로 판단하고 t+1 시가에 체결, t+2 시가까지의 수익을 t 포지션에 귀속한다.
 */
object StockBacktest {
    const val RULE = "golden_cross_50_200"
    private const val TRADING_DAYS = 252
    private const val MIN_EVAL_DAYS = 250

    fun run(candlesByTicker: Map<String, List<Candle>>, costPerSide: Double): BacktestResult? {
        val strategyByDate = TreeMap<LocalDate, MutableList<Double>>()
        val holdByDate = TreeMap<LocalDate, MutableList<Double>>()
        val tickers = candlesByTicker.mapNotNull { (ticker, candles) ->
            val sorted = candles.sortedBy { it.date }
            val states = StockTrend.states(sorted)
            val evalIdx = (StockTrend.SLOW_PERIOD until sorted.size).toList()
            if (evalIdx.size < MIN_EVAL_DAYS) {
                return@mapNotNull null
            }
            val returns = evalIdx.map { i ->
                if (i + 2 < sorted.size) sorted[i + 2].open / sorted[i + 1].open - 1 else 0.0
            }
            val dates = evalIdx.map { sorted[it].date }
            val positions = evalIdx.map { if (states[it] == TrendStateEnum.GOLDEN) 1.0 else 0.0 }
            val strategy = simulate(positions, returns, costPerSide)
            val hold = simulate(List(evalIdx.size) { 1.0 }, returns, costPerSide)
            dates.forEachIndexed { k, d ->
                strategyByDate.getOrPut(d) { mutableListOf() } += strategy.daily[k]
                holdByDate.getOrPut(d) { mutableListOf() } += hold.daily[k]
            }
            TickerBacktest(ticker, dates.first(), dates.last(), strategy.stats, hold.stats)
        }
        if (tickers.isEmpty()) {
            return null
        }
        return BacktestResult(
            rule = RULE,
            costPerSide = costPerSide,
            start = strategyByDate.firstKey(),
            end = strategyByDate.lastKey(),
            strategy = portfolioStats(strategyByDate, tickers.map { it.strategy }),
            buyAndHold = portfolioStats(holdByDate, tickers.map { it.buyAndHold }),
            tickers = tickers.sortedBy { it.ticker },
        )
    }

    private class Simulation(val daily: List<Double>, val stats: BacktestStats)

    private fun simulate(positions: List<Double>, returns: List<Double>, costPerSide: Double): Simulation {
        val trades = positions.mapIndexed { i, p -> if (i == 0) p else abs(p - positions[i - 1]) }
        val daily = positions.indices.map { i -> positions[i] * returns[i] - trades[i] * costPerSide }
        val years = daily.size.toDouble() / TRADING_DAYS
        return Simulation(daily, stats(daily, positions.average(), trades.sum() / years))
    }

    /** 노출 비율과 매매 횟수는 종목 평균 */
    private fun portfolioStats(byDate: TreeMap<LocalDate, MutableList<Double>>, perTicker: List<BacktestStats>) = stats(
        byDate.values.map { it.average() },
        perTicker.map { it.exposure }.average(),
        perTicker.map { it.tradesPerYear }.average(),
    )

    private fun stats(daily: List<Double>, exposure: Double, tradesPerYear: Double): BacktestStats {
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        for (r in daily) {
            equity *= 1 + r
            peak = maxOf(peak, equity)
            maxDrawdown = minOf(maxDrawdown, equity / peak - 1)
        }
        val years = daily.size.toDouble() / TRADING_DAYS
        val mean = daily.average()
        val std = sqrt(daily.sumOf { (it - mean).pow(2) } / (daily.size - 1))
        return BacktestStats(
            cagr = equity.pow(1 / years) - 1,
            maxDrawdown = maxDrawdown,
            sharpe = if (std > 0) mean / std * sqrt(TRADING_DAYS.toDouble()) else 0.0,
            exposure = exposure,
            tradesPerYear = tradesPerYear,
        )
    }
}
