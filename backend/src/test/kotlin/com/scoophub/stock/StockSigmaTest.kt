package com.scoophub.stock

import com.scoophub.stock.vo.OptionQuote
import com.scoophub.stock.vo.OptionsChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class StockSigmaTest {
    private val snapshotAt = Instant.parse("2026-10-06T22:30:00Z")

    private fun option(strike: Double, bid: Double, ask: Double, last: Double = 0.0, volume: Long = 0) =
        OptionQuote(strike = strike, bid = bid, ask = ask, lastPrice = last, volume = volume)

    private fun chain(expiry: String) = OptionsChain(
        expiry = LocalDate.parse(expiry),
        calls = listOf(option(755.0, 7.0, 7.2, volume = 100), option(760.0, 4.25, 4.39, volume = 9675)),
        puts = listOf(option(755.0, 2.0, 2.2, volume = 50), option(760.0, 0.0, 0.0, last = 4.2, volume = 24456)),
    )

    @Test
    fun `ATM 콜 중간가와 풋 체결가로 스트래들을 계산한다`() {
        // when
        val result = StockSigma.computeSigmaFromOptions(listOf(chain("2026-10-09")), "QQQ", 759.66, snapshotAt).single()

        // then
        assertThat(result.atmStrike).isEqualTo(760.0)
        assertThat(result.expectedMove).isEqualTo(8.52) // (4.25 + 4.39) / 2 + 4.2
        assertThat(result.expiryDate).isEqualTo(LocalDate.parse("2026-10-09"))
        assertThat(result.totalPutVolume).isEqualTo(24506)
        assertThat(result.snapshotDate).isEqualTo(LocalDate.parse("2026-10-06"))
        assertThat(result.source).isEqualTo("alpaca_straddle")
    }

    @Test
    fun `가까운 만기부터 MAX_EXPIRIES 개까지만 계산한다`() {
        // given
        val chains = (1..8).map { chain("2026-10-%02d".format(8 + it)) }

        // when
        val results = StockSigma.computeSigmaFromOptions(chains, "QQQ", 759.66, snapshotAt)

        // then
        assertThat(results).hasSize(StockSigma.MAX_EXPIRIES)
        assertThat(results.first().expiryDate).isEqualTo(LocalDate.parse("2026-10-09"))
    }
}
