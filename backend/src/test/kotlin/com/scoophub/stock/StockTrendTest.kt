package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StockTrendTest {

    private val start = LocalDate.parse("2020-01-01")

    private fun candles(closes: List<Double>) = closes.mapIndexed { i, c ->
        Candle("TST", "1D", start.plusDays(i.toLong()), c, c, c, c, 1.0)
    }

    @Test
    fun `200봉 미만이면 판단 불가다`() {
        // given
        val candles = candles(List(199) { 100.0 + it })

        // when
        val status = StockTrend.evaluate(candles)

        // then
        assertThat(status).isEqualTo(TrendStatus(TrendStateEnum.UNKNOWN, null))
    }

    @Test
    fun `200봉이 처음 찬 날은 교차로 보지 않아 시작일이 없다`() {
        // given — 계속 오름: 판단 가능해진 첫날부터 골든
        val candles = candles(List(260) { 100.0 + it })

        // when
        val status = StockTrend.evaluate(candles)

        // then
        assertThat(status).isEqualTo(TrendStatus(TrendStateEnum.GOLDEN, null))
    }

    @Test
    fun `하락 전환 뒤 50일선이 200일선 아래로 내려간 날이 데드크로스 시작일이다`() {
        // given — 250봉 상승 후 하락
        val closes = List(250) { 100.0 + it } + List(150) { 349.0 - 3 * (it + 1) }
        val candles = candles(closes)

        // when
        val status = StockTrend.evaluate(candles)

        // then
        val states = StockTrend.states(candles)
        val crossIdx = states.indexOfFirst { it == TrendStateEnum.DEAD }
        assertThat(states[crossIdx - 1]).isEqualTo(TrendStateEnum.GOLDEN)
        assertThat(status).isEqualTo(TrendStatus(TrendStateEnum.DEAD, candles[crossIdx].date))
    }

    @Test
    fun `상태는 SMA50 이 SMA200 보다 클 때만 골든이다`() {
        // given — 평탄하면 두 평균이 같아 골든이 아니다
        val candles = candles(List(200) { 100.0 })

        // when
        val states = StockTrend.states(candles)

        // then
        assertThat(states.take(199)).allMatch { it == TrendStateEnum.UNKNOWN }
        assertThat(states.last()).isEqualTo(TrendStateEnum.DEAD)
    }
}
