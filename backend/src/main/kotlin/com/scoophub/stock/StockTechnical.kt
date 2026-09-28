package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import java.time.LocalDate

/** legacy `stock/technical.py` — 해외 일봉 지표 (Wilder smoothing 등 수치 1:1) */
data class TechnicalResult(
    val ma5: Double,
    val ma20: Double,
    val ema12: Double,
    val ema26: Double,
    val rsi14: Double,
    val macdLine: Double,
    val macdSignal: Double,
    val macdHistogram: Double,
    val bbUpper: Double,
    val bbMiddle: Double,
    val bbLower: Double,
    val stochasticK: Double,
    val stochasticD: Double,
    val adx: Double,
    val atr: Double,
    val obv: Double,
    val vwap: Double,
    val bbWidth: Double = 0.0,
    val bbPctB: Double = 0.5,
    val obvTrendDir: Int = 0,
)

object StockTechnical {

    // ── Moving averages ────────────────────────────────────────────────

    fun movingAverage(closes: List<Double>, period: Int): Double {
        if (closes.size < period) {
            return 0.0
        }
        return closes.takeLast(period).sum() / period
    }

    fun ema(closes: List<Double>, period: Int): Double {
        if (closes.isEmpty()) {
            return 0.0
        }
        val k = 2.0 / (period + 1)
        var result = closes.first()
        for (price in closes.drop(1)) {
            result = price * k + result * (1 - k)
        }
        return result
    }

    // ── RSI — Wilder smoothing ─────────────────────────────────────────

    fun rsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size < period + 1) {
            return 50.0
        }
        val deltas = (1 until closes.size).map { closes[it] - closes[it - 1] }
        val gains = deltas.map { maxOf(it, 0.0) }
        val losses = deltas.map { maxOf(-it, 0.0) }

        var avgGain = gains.take(period).sum() / period
        var avgLoss = losses.take(period).sum() / period
        for (i in period until gains.size) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period
        }
        if (avgLoss == 0.0) {
            return 100.0
        }
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    // ── MACD ───────────────────────────────────────────────────────────

    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Triple<Double, Double, Double> {
        if (closes.size < slow) {
            return Triple(0.0, 0.0, 0.0)
        }
        val emaFast = emaSeries(closes, fast)
        val emaSlow = emaSeries(closes, slow)
        val macdSeries = emaFast.zip(emaSlow) { f, s -> f - s }
        val signalLine = if (macdSeries.size >= signal) ema(macdSeries, signal) else 0.0
        val macdLine = macdSeries.last()
        return Triple(macdLine, signalLine, macdLine - signalLine)
    }

    /** 입력 전체 길이에 정렬된 EMA 시계열 */
    private fun emaSeries(data: List<Double>, period: Int): List<Double> {
        if (data.size < period) {
            return List(data.size) { 0.0 }
        }
        val k = 2.0 / (period + 1)
        val result = MutableList(data.size) { 0.0 }
        result[period - 1] = data.take(period).sum() / period
        for (i in period until data.size) {
            result[i] = data[i] * k + result[i - 1] * (1 - k)
        }
        return result
    }

    // ── Bollinger Bands ────────────────────────────────────────────────

    fun bollingerBands(closes: List<Double>, period: Int = 20, stdMult: Double = 2.0): Triple<Double, Double, Double> {
        if (closes.size < period) {
            val last = closes.lastOrNull() ?: 0.0
            return Triple(last, last, last)
        }
        val window = closes.takeLast(period)
        val middle = window.sum() / period
        val variance = window.sumOf { (it - middle) * (it - middle) } / period
        val std = kotlin.math.sqrt(variance)
        return Triple(middle + stdMult * std, middle, middle - stdMult * std)
    }

    // ── Stochastic ─────────────────────────────────────────────────────

    fun stochastic(
        candles: List<Candle>,
        kPeriod: Int = 14,
        dPeriod: Int = 3,
        smoothPeriod: Int = 1,
    ): Pair<Double, Double> {
        val minCandles = kPeriod + smoothPeriod - 1
        if (candles.size < minCandles) {
            return 50.0 to 50.0
        }

        // Fast %K
        val fastK = mutableListOf<Double>()
        for (i in kPeriod - 1 until candles.size) {
            val window = candles.subList(i - kPeriod + 1, i + 1)
            val highest = window.maxOf { it.high }
            val lowest = window.minOf { it.low }
            val diff = highest - lowest
            fastK += if (diff == 0.0) 50.0 else (candles[i].close - lowest) / diff * 100
        }

        // Slow %K 평활 → %D
        val kValues = if (smoothPeriod <= 1 || fastK.size < smoothPeriod) {
            fastK
        } else {
            (smoothPeriod - 1 until fastK.size).map { i ->
                fastK.subList(i - smoothPeriod + 1, i + 1).sum() / smoothPeriod
            }
        }
        val k = kValues.last()
        val d = if (kValues.size >= dPeriod) kValues.takeLast(dPeriod).sum() / dPeriod else k
        return k to d
    }

    // ── ADX ────────────────────────────────────────────────────────────

    fun adx(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period * 2 + 1) {
            return 25.0
        }
        val trList = mutableListOf<Double>()
        val plusDm = mutableListOf<Double>()
        val minusDm = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val c = candles[i]
            val p = candles[i - 1]
            trList += maxOf(c.high - c.low, kotlin.math.abs(c.high - p.close), kotlin.math.abs(c.low - p.close))
            val up = c.high - p.high
            val down = p.low - c.low
            plusDm += if (up > down && up > 0) up else 0.0
            minusDm += if (down > up && down > 0) down else 0.0
        }
        if (trList.size < period) {
            return 25.0
        }

        var atrVal = trList.take(period).sum() / period
        var smoothPlus = plusDm.take(period).sum() / period
        var smoothMinus = minusDm.take(period).sum() / period

        val dxList = mutableListOf<Double>()
        for (i in period until trList.size) {
            atrVal = (atrVal * (period - 1) + trList[i]) / period
            smoothPlus = (smoothPlus * (period - 1) + plusDm[i]) / period
            smoothMinus = (smoothMinus * (period - 1) + minusDm[i]) / period

            val atrSafe = if (atrVal != 0.0) atrVal else 1e-10
            val plusDi = smoothPlus / atrSafe * 100
            val minusDi = smoothMinus / atrSafe * 100
            val diSum = plusDi + minusDi
            dxList += if (diSum != 0.0) kotlin.math.abs(plusDi - minusDi) / diSum * 100 else 0.0
        }

        if (dxList.size < period) {
            return dxList.lastOrNull() ?: 25.0
        }
        var adxVal = dxList.take(period).sum() / period
        for (i in period until dxList.size) {
            adxVal = (adxVal * (period - 1) + dxList[i]) / period
        }
        return adxVal
    }

    // ── ATR ────────────────────────────────────────────────────────────

    fun atr(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period + 1) {
            return 0.0
        }
        val trList = (1 until candles.size).map { i ->
            val c = candles[i]
            val p = candles[i - 1]
            maxOf(c.high - c.low, kotlin.math.abs(c.high - p.close), kotlin.math.abs(c.low - p.close))
        }
        var atrVal = trList.take(period).sum() / period
        for (i in period until trList.size) {
            atrVal = (atrVal * (period - 1) + trList[i]) / period
        }
        return atrVal
    }

    // ── OBV ────────────────────────────────────────────────────────────

    fun obv(candles: List<Candle>): Double {
        if (candles.isEmpty()) {
            return 0.0
        }
        var total = 0.0
        for (i in 1 until candles.size) {
            when {
                candles[i].close > candles[i - 1].close -> total += candles[i].volume
                candles[i].close < candles[i - 1].close -> total -= candles[i].volume
            }
        }
        return total
    }

    /** OBV 시계열 (trend 판정용) */
    private fun obvSeries(candles: List<Candle>): List<Double> {
        val series = mutableListOf<Double>()
        var total = 0.0
        for (i in 1 until candles.size) {
            when {
                candles[i].close > candles[i - 1].close -> total += candles[i].volume
                candles[i].close < candles[i - 1].close -> total -= candles[i].volume
            }
            series += total
        }
        return series
    }

    // ── VWAP ───────────────────────────────────────────────────────────

    fun vwap(candles: List<Candle>, period: Int = 20): Double {
        val window = if (candles.size >= period) candles.takeLast(period) else candles
        if (window.isEmpty()) {
            return 0.0
        }
        val cumVol = window.sumOf { it.volume }
        if (cumVol == 0.0) {
            return candles.lastOrNull()?.close ?: 0.0
        }
        return window.sumOf { (it.high + it.low + it.close) / 3 * it.volume } / cumVol
    }

    /** OBV trend direction: +1 rising, -1 falling, 0 flat */
    fun obvTrendDirection(candles: List<Candle>, period: Int = 10): Int {
        if (candles.size < period + 1) {
            return 0
        }
        val series = obvSeries(candles)
        if (series.size < period) {
            return 0
        }
        val recent = series.takeLast(period)
        return when {
            recent.last() > recent.first() -> 1
            recent.last() < recent.first() -> -1
            else -> 0
        }
    }

    // ── Aggregate ──────────────────────────────────────────────────────

    fun analyze(candles: List<Candle>): TechnicalResult {
        val closes = candles.map { it.close }

        val ma5 = movingAverage(closes, 5)
        val ma20 = movingAverage(closes, 20)
        val rsiVal = rsi(closes, 14)
        val (macdLine, macdSignal, macdHist) = macd(closes)
        val (bbUp, bbMid, bbLow) = bollingerBands(closes)
        val (stochK, stochD) = stochastic(candles)
        val adxVal = adx(candles)
        val atrVal = atr(candles)

        // BB width and %B
        val bbRange = bbUp - bbLow
        val bbWidth = if (bbMid > 0) bbRange / bbMid else 0.0
        val bbPctB = if (bbRange > 0) (closes.last() - bbLow) / bbRange else 0.5

        return TechnicalResult(
            ma5 = ma5,
            ma20 = ma20,
            ema12 = ema(closes, 12),
            ema26 = ema(closes, 26),
            rsi14 = rsiVal,
            macdLine = macdLine,
            macdSignal = macdSignal,
            macdHistogram = macdHist,
            bbUpper = bbUp,
            bbMiddle = bbMid,
            bbLower = bbLow,
            stochasticK = stochK,
            stochasticD = stochD,
            adx = adxVal,
            atr = atrVal,
            obv = obv(candles),
            vwap = vwap(candles),
            bbWidth = bbWidth,
            bbPctB = bbPctB,
            obvTrendDir = obvTrendDirection(candles),
        )
    }

    /** 지표별 점수 (-2 ~ +2). regime: TRENDING_UP/TRENDING_DOWN/RANGING/CHOPPY, null=중립 */
    fun technicalScore(result: TechnicalResult, currentPrice: Double, regime: String? = null): Map<String, Int> {
        val scores = mutableMapOf<String, Int>()
        val isUp = regime in setOf("TRENDING_UP", "TRENDING")
        val isDown = regime == "TRENDING_DOWN"

        // MA
        scores["ma"] = when {
            currentPrice > result.ma5 && currentPrice > result.ma20 -> 2
            currentPrice > result.ma5 -> 1
            currentPrice < result.ma5 && currentPrice < result.ma20 -> -2
            else -> -1
        }

        // RSI — regime-aware thresholds
        scores["rsi"] = when {
            isUp -> when {
                result.rsi14 < 30 -> 2
                result.rsi14 < 40 -> 1
                result.rsi14 > 70 -> 0
                result.rsi14 > 60 -> 1
                else -> 0
            }

            isDown -> when {
                result.rsi14 > 70 -> -2
                result.rsi14 > 60 -> -1
                result.rsi14 < 30 -> 0
                result.rsi14 < 40 -> -1
                else -> 0
            }

            else -> when {
                result.rsi14 < 30 -> 2
                result.rsi14 < 40 -> 1
                result.rsi14 > 70 -> -2
                result.rsi14 > 60 -> -1
                else -> 0
            }
        }

        // MACD
        scores["macd"] = when {
            result.macdHistogram > 0 && result.macdLine > result.macdSignal -> 2
            result.macdHistogram > 0 -> 1
            result.macdHistogram < 0 && result.macdLine < result.macdSignal -> -2
            else -> -1
        }

        // Bollinger Bands — %B 위치 + squeeze 컨텍스트
        val pctB = result.bbPctB
        val isSqueeze = result.bbWidth < 0.04 // 좁은 밴드 → 돌파 대기
        scores["bb"] = when {
            pctB < 0.0 -> 2
            pctB < 0.2 -> 1
            pctB > 1.0 -> -2
            pctB > 0.8 -> -1
            isSqueeze && isUp && pctB > 0.5 -> 1
            isSqueeze && isDown && pctB < 0.5 -> -1
            else -> 0
        }

        // Stochastic
        scores["stochastic"] = when {
            result.stochasticK < 20 -> 2
            result.stochasticK < 30 -> 1
            result.stochasticK > 80 -> -2
            result.stochasticK > 70 -> -1
            else -> 0
        }

        // ADX — 추세 강도의 방향 확인
        scores["adx"] = if (result.adx > 25) {
            if (currentPrice > result.ema12) 1 else -1
        } else {
            0
        }

        // VWAP
        scores["vwap"] = when {
            currentPrice > result.vwap * 1.02 -> 2
            currentPrice > result.vwap -> 1
            currentPrice < result.vwap * 0.98 -> -2
            currentPrice < result.vwap -> -1
            else -> 0
        }

        return scores
    }
}

/** 주간 그룹 경계 — 해당 날짜가 속한 주의 월요일(ISO) */
internal fun mondayOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())
