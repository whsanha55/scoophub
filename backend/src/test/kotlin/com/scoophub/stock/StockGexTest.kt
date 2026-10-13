package com.scoophub.stock

import com.scoophub.stock.vo.GexContract
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class StockGexTest {
    private fun call(strike: Double, oi: Long, gamma: Double = 0.01) = GexContract(strike, true, gamma, oi)

    private fun put(strike: Double, oi: Long, gamma: Double = 0.01) = GexContract(strike, false, gamma, oi)

    @Test
    fun `계약 GEX 는 OI 곱하기 감마 곱하기 100 곱하기 현재가 제곱 곱하기 0점01 이고 풋은 음수다`() {
        // when
        val result = requireNotNull(StockGex.compute(listOf(call(100.0, 10), put(100.0, 4)), spot = 100.0))

        // then — 콜 10×0.01×100×100²×0.01 = 1000, 풋 −400
        val strike = result.strikes.single()
        assertThat(strike.callGex).isCloseTo(1000.0, Offset.offset(1e-9))
        assertThat(strike.putGex).isCloseTo(-400.0, Offset.offset(1e-9))
        assertThat(strike.netGex).isCloseTo(600.0, Offset.offset(1e-9))
        assertThat(result.netGex).isCloseTo(600.0, Offset.offset(1e-9))
    }

    @Test
    fun `현재가 ±10% 밖 행사가와 OI 0 계약은 뺀다`() {
        // given
        val contracts = listOf(call(89.0, 100), call(111.0, 100), call(105.0, 0), call(95.0, 10))

        // when
        val result = requireNotNull(StockGex.compute(contracts, spot = 100.0))

        // then
        assertThat(result.strikes.map { it.strike }).containsExactly(95.0)
    }

    @Test
    fun `남는 계약이 없으면 null 이다`() {
        // when & then
        assertThat(StockGex.compute(listOf(call(150.0, 10)), spot = 100.0)).isNull()
    }

    @Test
    fun `저항은 현재가 위 양수 GEX, 지지는 아래 양수 GEX, 가속은 음수 GEX 를 크기순 2개씩 고른다`() {
        // given — 현재가 100, OI 1 당 GEX 100
        val contracts = listOf(
            call(108.0, 5),
            call(105.0, 30),
            call(103.0, 20),
            call(98.0, 15),
            call(96.0, 40),
            call(94.0, 1),
            put(97.0, 50),
            put(92.0, 80),
            put(91.0, 10),
        )

        // when
        val result = requireNotNull(StockGex.compute(contracts, spot = 100.0))

        // then
        assertThat(result.resistances).containsExactly(105.0, 103.0)
        assertThat(result.supports).containsExactly(96.0, 98.0)
        assertThat(result.accelerations).containsExactly(92.0, 97.0)
    }

    @Test
    fun `gamma flip 은 낮은 행사가부터 누적한 net GEX 가 부호를 바꾸는 지점을 선형 보간한다`() {
        // given — 누적: 95 → −1000, 100 → −1000 + 3000 = +2000
        val contracts = listOf(put(95.0, 10), call(100.0, 30))

        // when
        val result = requireNotNull(StockGex.compute(contracts, spot = 100.0))

        // then — 95 + 5 × 1000/3000
        assertThat(result.gammaFlip).isCloseTo(96.6667, Offset.offset(1e-4))
    }

    @Test
    fun `누적 net GEX 의 부호가 바뀌지 않으면 gamma flip 은 null 이다`() {
        // when
        val result = requireNotNull(StockGex.compute(listOf(call(95.0, 10), call(100.0, 30)), spot = 100.0))

        // then
        assertThat(result.gammaFlip).isNull()
    }

    @Test
    fun `부호가 여러 번 바뀌면 현재가에 가장 가까운 지점을 gamma flip 으로 쓴다`() {
        // given — 누적: 91 −1000, 93 +1000, 99 −1000, 101 +1000
        val contracts = listOf(put(91.0, 10), call(93.0, 20), put(99.0, 20), call(101.0, 20))

        // when
        val result = requireNotNull(StockGex.compute(contracts, spot = 100.0))

        // then — 99 와 101 사이 중간
        assertThat(result.gammaFlip).isCloseTo(100.0, Offset.offset(1e-9))
    }
}
