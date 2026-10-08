package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.pow

class StockBacktestTest {

    private val start = LocalDate.parse("2020-01-01")

    /** 시가 = 종가 = closes[i] */
    private fun candles(ticker: String, closes: List<Double>) = closes.mapIndexed { i, c ->
        Candle(ticker, "1D", start.plusDays(i.toLong()), c, c, c, c, 1.0)
    }

    @Test
    fun `평가 구간이 250일 미만인 종목만 있으면 null 이다`() {
        // given
        val data = mapOf("TST" to candles("TST", List(449) { 100.0 + it }))

        // when
        val result = StockBacktest.run(data, 0.0)

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `계속 골든이면 그냥 보유와 같고 진입 비용 한 번만 든다`() {
        // given — 매일 1% 상승, 시가 기준 수익
        val closes = List(452) { 100.0 * 1.01.pow(it) }
        val data = mapOf("TST" to candles("TST", closes))

        // when
        val result = requireNotNull(StockBacktest.run(data, 0.001))

        // then — 평가 252일 중 마지막 2일은 체결할 다음 시가가 없어 수익 0
        val ticker = result.tickers.single()
        assertThat(ticker.start).isEqualTo(start.plusDays(200))
        assertThat(ticker.strategy).isEqualTo(ticker.buyAndHold)
        assertThat(ticker.strategy.exposure).isEqualTo(1.0)
        val expectedEquity = 1.01.pow(250) * (1 + 0.01 - 0.001) / 1.01
        assertThat(ticker.strategy.cagr).isCloseTo(expectedEquity - 1, within(1e-9))
    }

    @Test
    fun `데드크로스 구간은 현금이라 하락을 피하고 전환 때마다 비용을 낸다`() {
        // given — 상승 250봉 → 급락 → 다시 상승
        val closes = List(250) { 100.0 + it } +
            List(120) { 349.0 - 2 * (it + 1) } +
            List(300) { 109.0 + 2 * (it + 1) }
        val data = mapOf("TST" to candles("TST", closes))

        // when
        val result = requireNotNull(StockBacktest.run(data, 0.001))

        // then
        val ticker = result.tickers.single()
        assertThat(ticker.strategy.exposure).isLessThan(1.0)
        assertThat(ticker.strategy.maxDrawdown).isGreaterThan(ticker.buyAndHold.maxDrawdown)
        assertThat(ticker.strategy.tradesPerYear).isGreaterThan(ticker.buyAndHold.tradesPerYear)
    }

    @Test
    fun `포트폴리오는 날짜별로 거래 중인 종목만 동일가중 평균한다`() {
        // given — 같은 수익 경로의 두 종목: 포트폴리오는 종목 하나와 같다
        val closes = List(500) { 100.0 + it }
        val data = mapOf("AAA" to candles("AAA", closes), "BBB" to candles("BBB", closes))

        // when
        val result = requireNotNull(StockBacktest.run(data, 0.0))

        // then
        assertThat(result.tickers.map { it.ticker }).containsExactly("AAA", "BBB")
        assertThat(result.strategy.cagr).isCloseTo(result.tickers[0].strategy.cagr, within(1e-12))
        assertThat(result.start).isEqualTo(start.plusDays(200))
        assertThat(result.end).isEqualTo(start.plusDays(499))
    }
}
