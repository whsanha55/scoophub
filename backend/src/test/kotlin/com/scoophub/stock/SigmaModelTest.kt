package com.scoophub.stock

import com.scoophub.stock.vo.SigmaModel
import com.scoophub.stock.vo.SigmaPosition
import com.scoophub.stock.vo.SigmaSignalType
import com.scoophub.stock.vo.WeeklyExpectedMove
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * legacy tests/test_stock_sigma.py 포팅 — weekly expected move(±1σ) 경계 회귀.
 *
 * crawler(cols[3]/cols[4])가 high/low를 ±1σ로 제공하므로 half_range 자체가 1σ.
 * 이전 `sigma = half_range/2` 버그(upper_1sigma가 실제 0.5σ) 방지.
 */
class SigmaModelTest {

    private fun wem(high: Double, low: Double) = WeeklyExpectedMove(
        ticker = "AAPL",
        weekStart = LocalDate.of(2024, 1, 1),
        weekEnd = LocalDate.of(2024, 1, 5),
        expectedMoveHigh = high,
        expectedMoveLow = low,
        expectedMovePct = 10.0,
    )

    @Test
    fun `±1시그마 상하한이 주간 예상움직임 high low 와 일치한다`() {
        // given
        val wem = wem(high = 110.0, low = 90.0)

        // when
        val range = SigmaModel.computeSigmaRange(wem, currentPrice = 100.0)

        // then
        assertThat(range.center).isEqualTo(100.0)
        assertThat(range.upper1sigma).isEqualTo(110.0) // +1σ == weekly high
        assertThat(range.lower1sigma).isEqualTo(90.0) // -1σ == weekly low
        assertThat(range.upper2sigma).isEqualTo(120.0)
        assertThat(range.lower2sigma).isEqualTo(80.0)
    }

    @Test
    fun `가격이 ±1시그마 범위 밖이면 극단 위치를 반환한다`() {
        // given & when
        val above = SigmaModel.computeSigmaRange(wem(110.0, 90.0), currentPrice = 115.0)
        val below = SigmaModel.computeSigmaRange(wem(110.0, 90.0), currentPrice = 85.0)

        // then
        assertThat(above.sigmaPosition).isEqualTo(SigmaPosition.ABOVE_1SIGMA)
        assertThat(below.sigmaPosition).isEqualTo(SigmaPosition.BELOW_1SIGMA)
    }

    @Test
    fun `가격이 center 부근이면 NEAR_CENTER 를 반환한다`() {
        // when
        val range = SigmaModel.computeSigmaRange(wem(110.0, 90.0), currentPrice = 100.0)

        // then
        assertThat(range.sigmaPosition).isEqualTo(SigmaPosition.NEAR_CENTER)
    }

    @Test
    fun `시그마 위치에 따라 역추세 시그널과 신뢰도를 매핑한다`() {
        // given & when
        fun signalAt(price: Double) = SigmaModel.generateSigmaSignal(
            SigmaModel.computeSigmaRange(wem(110.0, 90.0), currentPrice = price),
        )
        val below = signalAt(price = 85.0)
        val center = signalAt(price = 100.0)
        val above = signalAt(price = 115.0)

        // then — 하방 극단 매수, 상방 극단 매도 (contrarian)
        assertThat(below.signal).isEqualTo(SigmaSignalType.STRONG_BUY)
        assertThat(below.confidence).isEqualTo(0.8)
        assertThat(center.signal).isEqualTo(SigmaSignalType.NEUTRAL)
        assertThat(center.confidence).isEqualTo(0.3)
        assertThat(above.signal).isEqualTo(SigmaSignalType.STRONG_SELL)
        assertThat(above.confidence).isEqualTo(0.8)
    }
}
