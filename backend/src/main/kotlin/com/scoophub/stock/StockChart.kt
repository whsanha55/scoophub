package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import java.time.LocalDate

/** 차트 한 점. cross 는 그날 50/200 교차로 바뀐 추세 상태 */
data class ChartPoint(
    val date: LocalDate,
    val close: Double,
    val sma50: Double?,
    val sma200: Double?,
    val band: SigmaBand?,
    val position: Double?,
    val state: SigmaStateEnum?,
    val cross: TrendStateEnum?,
)

/** 상세 차트 — 종가, SMA50/200, 골든·데드크로스, 날짜별 주간 σ 밴드 (#261) */
object StockChart {

    /** SMA 가 앞 구간부터 채워지도록 candles 는 자르지 않은 전체 일봉을 넘긴다 */
    fun points(candles: List<Candle>, bands: List<SigmaBand>): List<ChartPoint> {
        val closes = candles.map { it.close }
        val sma50 = sma(closes, StockTrend.FAST_PERIOD)
        val sma200 = sma(closes, StockTrend.SLOW_PERIOD)
        val states = StockTrend.states(candles)
        return candles.mapIndexed { i, candle ->
            val band = StockSigmaBand.bandOn(bands, candle.date)
            val position = band?.position(candle.close)
            ChartPoint(
                date = candle.date,
                close = candle.close,
                sma50 = sma50[i],
                sma200 = sma200[i],
                band = band,
                position = position,
                state = position?.let { StockSigmaBand.state(it) },
                cross = states[i].takeIf { i > 0 && it != states[i - 1] && states[i - 1] != TrendStateEnum.UNKNOWN },
            )
        }
    }

    private fun sma(closes: List<Double>, period: Int): List<Double?> {
        var sum = 0.0
        return closes.indices.map { i ->
            sum += closes[i]
            if (i >= period) {
                sum -= closes[i - period]
            }
            if (i >= period - 1) sum / period else null
        }
    }
}
