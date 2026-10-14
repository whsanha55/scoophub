package com.scoophub.stock

import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.external.alpaca.AlpacaMarketDataException
import com.scoophub.stock.repository.StockGexQueryRepository
import com.scoophub.stock.service.StockGexService
import com.scoophub.stock.vo.GexChain
import com.scoophub.stock.vo.GexContract
import com.scoophub.stock.vo.Quote
import com.scoophub.stock.vo.StockGexRow
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

class StockGexServiceTest {
    // 2026-10-12 17:30 ET (KST 10-13 06:30)
    private val clock = Clock.fixed(Instant.parse("2026-10-12T21:30:00Z"), ZoneOffset.UTC)
    private val provider = mockk<AlpacaMarketDataClient>()
    private val repository = mockk<StockGexQueryRepository>()
    private val service = StockGexService(provider, repository, clock, Duration.ZERO)

    private fun quote(price: Double) = Quote(price, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun `현재가 ±10% 행사가 범위로 GEX 입력을 받아 ET 날짜로 저장한다`() {
        // given
        every { provider.snapshots(listOf("SOXX")) } returns mapOf("SOXX" to quote(500.0))
        every { provider.gexChain("SOXX", any(), any()) } returns GexChain(
            oiDate = LocalDate.parse("2026-10-12"),
            contracts = listOf(GexContract(510.0, true, 0.01, 100)),
        )
        val saved = slot<StockGexRow>()
        every { repository.upsert(capture(saved)) } just runs

        // when
        val outcome = service.computeGex(listOf("SOXX"))

        // then
        assertThat(outcome.saved).isEqualTo(1)
        io.mockk.verify {
            provider.gexChain("SOXX", match { abs(it - 450.0) < 1e-6 }, match { abs(it - 550.0) < 1e-6 })
        }
        assertThat(saved.captured.snapshotDate).isEqualTo(LocalDate.parse("2026-10-12"))
        assertThat(saved.captured.oiDate).isEqualTo(LocalDate.parse("2026-10-12"))
        assertThat(saved.captured.resistances).containsExactly(510.0)
        assertThat(saved.captured.netGex).isCloseTo(250_000.0, Offset.offset(1e-6))
    }

    @Test
    fun `현재가가 없거나 종목 조회가 실패하면 그 종목만 실패로 남긴다`() {
        // given
        every { provider.snapshots(listOf("NONE", "SOXX")) } returns mapOf("SOXX" to quote(500.0))
        every { provider.gexChain("SOXX", any(), any()) } throws AlpacaMarketDataException(403, "HTTP 403")

        // when
        val outcome = service.computeGex(listOf("NONE", "SOXX"))

        // then
        assertThat(outcome.saved).isZero()
        assertThat(outcome.failures).containsEntry("NONE", "price unavailable").containsEntry("SOXX", "HTTP 403")
    }

    @Test
    fun `미결제약정이나 감마가 있는 계약이 없으면 실패로 세지 않고 건너뛴다`() {
        // given
        every { provider.snapshots(listOf("XLC")) } returns mapOf("XLC" to quote(112.0))
        every { provider.gexChain("XLC", any(), any()) } returns GexChain(oiDate = null, contracts = emptyList())

        // when
        val outcome = service.computeGex(listOf("XLC"))

        // then
        assertThat(outcome.saved).isZero()
        assertThat(outcome.failures).isEmpty()
    }
}
