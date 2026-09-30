package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private val log = KotlinLogging.logger {}

enum class Signal { STRONG_BUY, BUY, HOLD, SELL, STRONG_SELL }

enum class MarketRegime { TRENDING_UP, TRENDING_DOWN, RANGING, CHOPPY }

data class AnalysisReport(
    val ticker: String,
    val signal: Signal,
    val totalScore: Double,
    val confidence: Double,
    val marketRegime: MarketRegime,
    val technicalScores: Map<String, Int>,
    val technicalDetails: TechnicalResult,
)

/** legacy `stock/signal.py` — 지표 조합 → BUY/SELL/HOLD 신호 생성 */
object StockSignal {

    fun detectRegime(result: TechnicalResult, price: Double): MarketRegime {
        // Choppy: 매우 낮은 ADX(방향성 없음) + 넓은 BB(변동성 높음) = 노이즈 시장
        if (result.adx < 15 && result.bbWidth > 0.06) {
            return MarketRegime.CHOPPY
        }
        // Ranging: 낮은 ADX = 뚜렷한 추세 없음 (박스권)
        if (result.adx < 20) {
            return MarketRegime.RANGING
        }
        if (price > result.ema12 && result.macdLine > result.macdSignal) {
            return MarketRegime.TRENDING_UP
        }
        if (price < result.ema12 && result.macdLine < result.macdSignal) {
            return MarketRegime.TRENDING_DOWN
        }
        return MarketRegime.RANGING
    }

    /** 국면별 지표 가중치 — 추세장은 추세 지표 ↑, 박스권은 평균회귀 지표 ↑ */
    fun dynamicWeights(regime: MarketRegime): Map<String, Double> = when (regime) {
        MarketRegime.TRENDING_UP -> mapOf(
            "ma" to 1.2,
            "rsi" to 0.8,
            "macd" to 1.5,
            "bb" to 0.6,
            "stochastic" to 0.5,
            "adx" to 1.0,
            "vwap" to 1.0,
        )

        MarketRegime.TRENDING_DOWN -> mapOf(
            "ma" to 1.2,
            "rsi" to 1.3,
            "macd" to 1.5,
            "bb" to 0.8,
            "stochastic" to 0.6,
            "adx" to 1.0,
            "vwap" to 1.0,
        )

        MarketRegime.CHOPPY -> mapOf(
            "ma" to 0.5,
            "rsi" to 0.6,
            "macd" to 0.4,
            "bb" to 0.5,
            "stochastic" to 0.5,
            "adx" to 0.3,
            "vwap" to 0.5,
        )

        MarketRegime.RANGING -> mapOf(
            "ma" to 0.7,
            "rsi" to 1.3,
            "macd" to 0.6,
            "bb" to 1.4,
            "stochastic" to 1.3,
            "adx" to 0.4,
            "vwap" to 1.1,
        )
    }

    /** 신뢰도 0~100 — 지표 합의도(60%) + 강도(30%) + 국면 보정 */
    fun calcConfidence(scores: Map<String, Int>, weights: Map<String, Double>, regime: MarketRegime): Double {
        if (scores.isEmpty()) {
            return 0.0
        }
        val weightedSum = scores.entries.sumOf { (k, v) -> v * (weights[k] ?: 1.0) }
        val maxPossible = scores.entries.sumOf { (k, v) -> abs(v) * (weights[k] ?: 1.0) }
        if (maxPossible == 0.0) {
            return 50.0
        }

        val direction = when {
            weightedSum > 0 -> 1
            weightedSum < 0 -> -1
            else -> 0
        }
        val agreementRatio = if (direction != 0) {
            val agreeing = scores.values.count { v -> (v > 0 && direction > 0) || (v < 0 && direction < 0) }
            val totalActive = scores.values.count { it != 0 }
            if (totalActive > 0) agreeing.toDouble() / totalActive else 0.0
        } else {
            0.0
        }
        val strength = abs(weightedSum) / maxPossible
        var baseConf = agreementRatio * 60 + strength * 30

        // 추세장 보너스(+10), 혼조장 페널티(-15)
        when (regime) {
            MarketRegime.TRENDING_UP, MarketRegime.TRENDING_DOWN -> baseConf += 10
            MarketRegime.CHOPPY -> baseConf -= 15
            else -> {}
        }
        return round1(baseConf.coerceIn(0.0, 100.0))
    }

    fun scoreToSignal(score: Double): Signal = when {
        score >= 6 -> Signal.STRONG_BUY
        score >= 2 -> Signal.BUY
        score <= -6 -> Signal.STRONG_SELL
        score <= -2 -> Signal.SELL
        else -> Signal.HOLD
    }

    /** 지표 전체 분석 리포트 생성 */
    fun generateReport(
        ticker: String,
        price: Double,
        dailyCandles: List<Candle>,
        today: LocalDate = LocalDate.now(),
    ): AnalysisReport {
        // 캔들 없으면 당일 가격 1봉으로 폴백
        val candles = dailyCandles.ifEmpty {
            listOf(
                Candle(
                    ticker = ticker,
                    interval = "1D",
                    date = today,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = 0.0,
                ),
            )
        }

        val techResult = StockTechnical.analyze(candles)
        val regime = detectRegime(techResult, price)
        val weights = dynamicWeights(regime)
        val techScores = StockTechnical.technicalScore(techResult, price, regime.name)

        val techTotal = techScores.entries.sumOf { (k, v) -> v * (weights[k] ?: 1.0) }

        // 거래량 다이버전스: 신호 방향과 OBV 방향 불일치
        val volDivergence = (techTotal > 0 && techResult.obvTrendDir < 0) ||
            (techTotal < 0 && techResult.obvTrendDir > 0)

        val signal = scoreToSignal(techTotal)
        var confidence = calcConfidence(techScores, weights, regime)
        if (volDivergence) {
            confidence = round1(confidence * 0.75) // 다이버전스 시 25% 차감
        }

        val quality = when {
            confidence >= 70 -> "strong"
            confidence >= 45 -> "moderate"
            else -> "weak"
        }

        log.info {
            "generate_report: completed — ticker=$ticker, signal=$signal, score=${round1(techTotal)}, " +
                "confidence=$confidence, regime=$regime, quality=$quality"
        }

        return AnalysisReport(
            ticker = ticker,
            signal = signal,
            totalScore = round1(techTotal),
            confidence = confidence,
            marketRegime = regime,
            technicalScores = techScores,
            technicalDetails = techResult,
        )
    }

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
