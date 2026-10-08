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
        val result = requireNotNull(
            StockSigma.computeSigmaFromOptions(listOf(chain("2026-10-09")), "QQQ", 759.66, snapshotAt),
        )

        // then
        assertThat(result.atmStrike).isEqualTo(760.0)
        assertThat(result.expectedMove).isEqualTo(8.52) // (4.25 + 4.39) / 2 + 4.2
        assertThat(result.expiryDate).isEqualTo(LocalDate.parse("2026-10-09"))
        assertThat(result.totalPutVolume).isEqualTo(24506)
        assertThat(result.snapshotDate).isEqualTo(LocalDate.parse("2026-10-06"))
        assertThat(result.source).isEqualTo("alpaca_straddle")
    }

    @Test
    fun `당일 만기를 빼고 가장 가까운 만기가 속한 주의 마지막 만기를 쓴다`() {
        // given — 스냅샷 ET 2026-10-06(화). 당일·수·목·금·다음 주 만기
        val chains = listOf("2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-16").map(::chain)

        // when
        val result = StockSigma.computeSigmaFromOptions(chains, "QQQ", 759.66, snapshotAt)

        // then
        assertThat(result?.expiryDate).isEqualTo(LocalDate.parse("2026-10-09"))
    }

    @Test
    fun `금요일 휴장 주에는 목요일 만기를 쓴다`() {
        // given
        val chains = listOf("2026-10-07", "2026-10-08", "2026-10-16").map(::chain)

        // when
        val result = StockSigma.computeSigmaFromOptions(chains, "QQQ", 759.66, snapshotAt)

        // then
        assertThat(result?.expiryDate).isEqualTo(LocalDate.parse("2026-10-08"))
    }

    @Test
    fun `금요일 스냅샷이면 다음 주 금요일 만기를 쓴다`() {
        // given — ET 2026-10-09(금) 장 마감 후
        val fridayClose = Instant.parse("2026-10-09T21:00:00Z")
        val chains = listOf("2026-10-09", "2026-10-12", "2026-10-14", "2026-10-16").map(::chain)

        // when
        val result = StockSigma.computeSigmaFromOptions(chains, "QQQ", 759.66, fridayClose)

        // then
        assertThat(result?.expiryDate).isEqualTo(LocalDate.parse("2026-10-16"))
    }

    @Test
    fun `다가오는 만기가 없으면 null 이다`() {
        // when
        val result = StockSigma.computeSigmaFromOptions(listOf(chain("2026-10-06")), "QQQ", 759.66, snapshotAt)

        // then
        assertThat(result).isNull()
    }
}
