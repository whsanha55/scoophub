package com.scoophub.stock

import com.scoophub.stock.vo.SigmaModel
import com.scoophub.stock.vo.SigmaRange
import com.scoophub.stock.vo.WeeklyExpectedMove
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * legacy tests/test_stock_report.py 포팅 — T4(compute_actionable_levels) +
 * sigma_range_from_snapshot 부분. StockReportBuilder companion 순수 함수 대상.
 */
class StockReportLevelsTest {

    private val jsonMapper = JsonMapper.builder().build()

    private fun details(json: String): JsonNode = jsonMapper.readTree(json)

    private fun sigma(high: Double, low: Double, price: Double): SigmaRange = SigmaModel.computeSigmaRange(
        WeeklyExpectedMove(
            ticker = "AAPL",
            weekStart = LocalDate.of(2024, 1, 1),
            weekEnd = LocalDate.of(2024, 1, 5),
            expectedMoveHigh = high,
            expectedMoveLow = low,
            expectedMovePct = 10.0,
        ),
        currentPrice = price,
    )

    @Test
    fun `목표가는 +1시그마 매수구간은 -1시그마이고 손절가는 진입가에서 ATR 1점5배를 뺀 값이다`() {
        // given
        val sr = sigma(110.0, 90.0, price = 100.0)

        // when
        val levels = StockReportBuilder.computeActionableLevels(
            price = 100.0,
            sigmaRange = sr,
            techDetails = details("""{"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.5}"""),
        )

        // then
        val lv = requireNotNull(levels)
        assertThat(lv.targetPrice).isEqualTo(110.0) // upper_1sigma
        assertThat(lv.buyZone).isEqualTo(90.0) // lower_1sigma
        assertThat(lv.stopLoss).isCloseTo(100.0 - 1.5 * 4.0, within(1e-9)) // 현재가 기준 (momentum_fire)
        assertThat(lv.momentumFire).isTrue() // price(100) > ema12(99) & macd_hist > 0
    }

    @Test
    fun `가격이 EMA12 보다 낮으면 momentum_fire 가 false 이고 손절가는 매수구간 기준이다`() {
        // given
        val sr = sigma(110.0, 90.0, price = 95.0)

        // when
        val levels = StockReportBuilder.computeActionableLevels(
            price = 95.0,
            sigmaRange = sr,
            techDetails = details("""{"atr": 3.0, "ema12": 100.0, "macd_histogram": 0.5}"""),
        )

        // then
        val lv = requireNotNull(levels)
        assertThat(lv.momentumFire).isFalse()
        assertThat(lv.stopLoss).isCloseTo(90.0 - 1.5 * 3.0, within(1e-9)) // buy_zone 기준
    }

    @Test
    fun `MACD 히스토그램이 음수면 momentum_fire 가 false 이다`() {
        // given
        val sr = sigma(110.0, 90.0, price = 105.0)

        // when
        val levels = StockReportBuilder.computeActionableLevels(
            price = 105.0,
            sigmaRange = sr,
            techDetails = details("""{"atr": 3.0, "ema12": 100.0, "macd_histogram": -0.3}"""),
        )

        // then
        val lv = requireNotNull(levels)
        assertThat(lv.momentumFire).isFalse()
        assertThat(lv.stopLoss).isCloseTo(90.0 - 1.5 * 3.0, within(1e-9)) // buy_zone 기준
    }

    @Test
    fun `ATR 이 없으면 손절가는 null 이다`() {
        // given
        val sr = sigma(110.0, 90.0, price = 100.0)

        // when
        val levels = StockReportBuilder.computeActionableLevels(100.0, sr, details("{}"))

        // then
        val lv = requireNotNull(levels)
        assertThat(lv.stopLoss).isNull()
        assertThat(lv.targetPrice).isEqualTo(110.0)
        assertThat(lv.buyZone).isEqualTo(90.0)
    }

    @Test
    fun `가격이 0이면 null 을 반환한다`() {
        // given
        val sr = sigma(110.0, 90.0, price = 100.0)

        // when & then
        assertThat(StockReportBuilder.computeActionableLevels(0.0, sr, details("{}"))).isNull()
    }

    @Test
    fun `시그마 구간과 BB 밴드가 모두 없으면 null 을 반환한다`() {
        assertThat(StockReportBuilder.computeActionableLevels(100.0, null, details("{}"))).isNull()
    }

    @Test
    fun `시그마 부재 시 BB 밴드로 목표가와 매수구간을 폴백한다`() {
        // when
        val levels = StockReportBuilder.computeActionableLevels(
            price = 100.0,
            sigmaRange = null,
            techDetails = details(
                """{"atr": 4.0, "bb_upper": 108.0, "bb_lower": 92.0, "ema12": 99.0, "macd_histogram": 0.5}""",
            ),
        )

        // then
        val lv = requireNotNull(levels)
        assertThat(lv.targetPrice).isEqualTo(108.0) // bb_upper 폴백
        assertThat(lv.buyZone).isEqualTo(92.0) // bb_lower 폴백
        assertThat(lv.stopLoss).isCloseTo(100.0 - 1.5 * 4.0, within(1e-9)) // 현재가 기준 (momentum_fire)
        assertThat(lv.momentumFire).isTrue()
    }

    @Test
    fun `시그마가 있으면 BB 보다 시그마 ±1시그마를 우선한다`() {
        // given
        val sr = sigma(110.0, 90.0, price = 100.0)

        // when
        val levels = StockReportBuilder.computeActionableLevels(
            price = 100.0,
            sigmaRange = sr,
            techDetails = details(
                """{"atr": 4.0, "bb_upper": 108.0, "bb_lower": 92.0, "ema12": 99.0, "macd_histogram": 0.5}""",
            ),
        )

        // then
        val lv = requireNotNull(levels)
        assertThat(lv.targetPrice).isEqualTo(110.0) // sigma upper, not bb_upper
        assertThat(lv.buyZone).isEqualTo(90.0) // sigma lower, not bb_lower
    }

    @Test
    fun `straddle expected_move 로 현재가 중심 ±1시그마 구간을 만든다`() {
        // when
        val sr = StockReportBuilder.sigmaRangeFromSnapshot(
            details("""{"straddle": {"expected_move": 10.0}}"""),
            price = 100.0,
        )

        // then
        val range = requireNotNull(sr)
        assertThat(range.center).isEqualTo(100.0)
        assertThat(range.upper1sigma).isCloseTo(110.0, within(1e-9))
        assertThat(range.lower1sigma).isCloseTo(90.0, within(1e-9))
    }

    @Test
    fun `straddle 부재 시 WEM 스냅샷의 ±1시그마로 폴백한다`() {
        // when
        val sr = StockReportBuilder.sigmaRangeFromSnapshot(
            details("""{"weekly_expected_move": {"upper_1sigma": 110.0, "lower_1sigma": 90.0, "center": 100.0}}"""),
            price = 100.0,
        )

        // then
        val range = requireNotNull(sr)
        assertThat(range.upper1sigma).isCloseTo(110.0, within(1e-9))
        assertThat(range.lower1sigma).isCloseTo(90.0, within(1e-9))
    }

    @Test
    fun `straddle 과 WEM 모두 없으면 null 을 반환한다`() {
        // given & when & then — levels 산출 스킵
        assertThat(StockReportBuilder.sigmaRangeFromSnapshot(details("{}"), price = 100.0)).isNull()
        assertThat(StockReportBuilder.sigmaRangeFromSnapshot(details("""{"straddle": {}}"""), price = 100.0)).isNull()
    }

    @Test
    fun `fmtPrice 는 null 을 N-A 로 포맷한다`() {
        assertThat(StockReportBuilder.fmtPrice(null)).isEqualTo("N/A")
    }

    @Test
    fun `fmtPrice 는 가격을 천단위 콤마와 소수 둘째 자리로 포맷한다`() {
        assertThat(StockReportBuilder.fmtPrice(1234.5)).isEqualTo("$1,234.50")
        assertThat(StockReportBuilder.fmtPrice(100.0)).isEqualTo("$100.00")
    }
}
