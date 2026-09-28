package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.sin

/**
 * legacy `app/stock/technical.py` 순수 함수과의 수치 비교(parity) 테스트.
 *
 * 기대값은 동일 픽스처를 legacy python 함수로 실행해 계산한 값(하드코딩).
 * 픽스처 — 결정론적 60개 일봉:
 * i번째 close = 100 + 3*sin(i) + (i%7), high = close+2, low = close-2,
 * open = close-1, volume = 1000+i
 */
class StockTechnicalParityTest {

    private val candles: List<Candle> = (0 until 60).map { i ->
        val close = 100 + 3 * sin(i.toDouble()) + (i % 7)
        Candle(
            ticker = "TST",
            interval = "1D",
            date = LocalDate.of(2024, 1, 1).plusDays(i.toLong()),
            open = close - 1,
            high = close + 2,
            low = close - 2,
            close = close,
            volume = (1000 + i).toDouble(),
        )
    }
    private val closes: List<Double> = candles.map { it.close }

    @Test
    fun `SMA와 EMA가 python 계산 결과와 1e-9 오차로 일치한다`() {
        // when
        val ma5 = StockTechnical.movingAverage(closes, 5)
        val ma20 = StockTechnical.movingAverage(closes, 20)
        val ema12 = StockTechnical.ema(closes, 12)
        val ema26 = StockTechnical.ema(closes, 26)

        // then
        assertThat(ma5).isCloseTo(102.72668154101557, within(1e-9))
        assertThat(ma20).isCloseTo(103.06793615864254, within(1e-9))
        assertThat(ema12).isCloseTo(103.21970145702007, within(1e-9))
        assertThat(ema26).isCloseTo(103.06101325235946, within(1e-9))
    }

    @Test
    fun `RSI 14 Wilder smoothing 값이 python 계산 결과와 일치한다`() {
        // when
        val rsi14 = StockTechnical.rsi(closes, 14)

        // then
        assertThat(rsi14).isCloseTo(52.904176773830194, within(1e-9))
    }

    @Test
    fun `MACD 12-26-9 라인과 시그널과 히스토그램이 python 계산 결과와 일치한다`() {
        // when
        val (macdLine, macdSignal, macdHistogram) = StockTechnical.macd(closes)

        // then
        assertThat(macdLine).isCloseTo(0.1224947505587437, within(1e-9))
        assertThat(macdSignal).isCloseTo(0.02361178104447352, within(1e-9))
        assertThat(macdHistogram).isCloseTo(0.09888296951427018, within(1e-9))
    }

    @Test
    fun `볼린저 밴드 20 2시그마가 python 계산 결과와 일치한다`() {
        // when
        val (upper, middle, lower) = StockTechnical.bollingerBands(closes)

        // then
        assertThat(upper).isCloseTo(109.23888083372835, within(1e-9))
        assertThat(middle).isCloseTo(103.06793615864254, within(1e-9))
        assertThat(lower).isCloseTo(96.89699148355673, within(1e-9))
    }

    @Test
    fun `Stochastic K D 값이 python 계산 결과와 일치한다`() {
        // when
        val (k, d) = StockTechnical.stochastic(candles)

        // then
        assertThat(k).isCloseTo(72.0258238090174, within(1e-9))
        assertThat(d).isCloseTo(65.80144054967809, within(1e-9))
    }

    @Test
    fun `ADX 14와 ATR 14가 python 계산 결과와 일치한다`() {
        // when
        val adxVal = StockTechnical.adx(candles)
        val atrVal = StockTechnical.atr(candles)

        // then
        assertThat(adxVal).isCloseTo(6.8947260263270005, within(1e-9))
        assertThat(atrVal).isCloseTo(5.041131880078031, within(1e-9))
    }

    @Test
    fun `OBV와 VWAP과 OBV 추세 방향이 python 계산 결과와 일치한다`() {
        // when
        val obvVal = StockTechnical.obv(candles)
        val vwapVal = StockTechnical.vwap(candles)
        val obvDir = StockTechnical.obvTrendDirection(candles)

        // then
        assertThat(obvVal).isCloseTo(3094.0, within(1e-9))
        assertThat(vwapVal).isCloseTo(103.06813205861963, within(1e-9))
        assertThat(obvDir).isEqualTo(-1)
    }

    @Test
    fun `analyze 종합 결과의 파생 지표가 python 계산 결과와 일치한다`() {
        // when
        val result = StockTechnical.analyze(candles)

        // then — 개별 지표는 위 테스트들에서 검증, 여기선 bb_width/bb_pct_b/obv_trend_dir
        assertThat(result.bbWidth).isCloseTo(0.11974518759330673, within(1e-9))
        assertThat(result.bbPctB).isCloseTo(0.6492703272979239, within(1e-9))
        assertThat(result.obvTrendDir).isEqualTo(-1)
    }
}
