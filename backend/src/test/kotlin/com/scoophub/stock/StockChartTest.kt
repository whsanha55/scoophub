package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** 상세 차트 포인트 — SMA50/200, 골든·데드크로스, 날짜별 σ 밴드 (#261) */
class StockChartTest {

    private val start = LocalDate.parse("2026-01-01")

    private fun candles(closes: List<Double>) = closes.mapIndexed { i, close ->
        Candle("QQQ", "1D", start.plusDays(i.toLong()), close, close, close, close, 0.0)
    }

    @Test
    fun `SMA 는 기간만큼 일봉이 찬 날부터 채운다`() {
        // given
        val points = StockChart.points(candles((1..200).map { it.toDouble() }), emptyList())

        // when & then
        assertThat(points[48].sma50).isNull()
        assertThat(points[49].sma50).isCloseTo(25.5, within(1e-9))
        assertThat(points[198].sma200).isNull()
        assertThat(points[199].sma200).isCloseTo(100.5, within(1e-9))
    }

    @Test
    fun `교차 마커는 추세 상태가 바뀐 날에만 붙고 판단 가능해진 첫날은 교차가 아니다`() {
        // given — 220일 오른 뒤 내리막: 200봉이 찬 날은 GOLDEN 시작일 뿐, 이후 50일선이 200일선 아래로 내려간 날이 데드크로스
        val closes = (1..220).map { it.toDouble() } + (1..120).map { 220.0 - it * 3 }
        val points = StockChart.points(candles(closes), emptyList())

        // when
        val crosses = points.filter { it.cross != null }

        // then
        assertThat(crosses).hasSize(1)
        assertThat(crosses.single().cross).isEqualTo(TrendStateEnum.DEAD)
    }

    @Test
    fun `밴드가 적용되는 날은 σ 위치와 상태를 붙이고 아니면 비운다`() {
        // given — 01-02 앵커(100 ± 5), 만기 01-04
        val band = SigmaBand(start.plusDays(1), start.plusDays(3), anchorPrice = 100.0, expectedMove = 5.0)
        val points = StockChart.points(candles(listOf(100.0, 100.0, 103.0, 106.0, 90.0)), listOf(band))

        // when & then
        assertThat(points.map { it.band }).containsExactly(null, null, band, band, null)
        assertThat(points[2].position).isCloseTo(0.6, within(1e-9))
        assertThat(points[2].state).isEqualTo(SigmaStateEnum.NORMAL)
        assertThat(points[3].state).isEqualTo(SigmaStateEnum.UPPER_BREAK)
        assertThat(points[4].state).isNull()
    }
}
