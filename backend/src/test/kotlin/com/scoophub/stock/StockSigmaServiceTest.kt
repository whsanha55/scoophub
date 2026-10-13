package com.scoophub.stock

import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockCandleEntity
import com.scoophub.stock.entity.StockSigmaEntity
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.service.StockSigmaService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/** 전 거래일 σ 상태 (#267 신규·지속 구분) — repository 만 목킹한 단위 테스트 */
class StockSigmaServiceTest {

    private val sigmaRepository = mockk<StockSigmaRepository>()
    private val candleRepository = mockk<StockCandleRepository>()
    private val service = StockSigmaService(sigmaRepository, candleRepository)

    private fun row(ticker: String, candleDate: LocalDate) = StockAnalysisResultEntity(
        ticker = ticker,
        exchange = "NAS",
        timeframe = "1D",
        candleDate = candleDate,
        price = 100.0,
        change = 0.0,
        changeRate = 0.0,
        technicalDetails = JsonMapper.builder().build().readTree("{}"),
        analyzedAt = Instant.parse("2024-01-10T00:00:00Z"),
    )

    private fun snapshot(ticker: String, snapshotDate: String, expiryDate: String, anchor: Double) = StockSigmaEntity(
        ticker = ticker,
        expiryDate = LocalDate.parse(expiryDate),
        snapshotDate = LocalDate.parse(snapshotDate),
        snapshotAt = Instant.parse("2024-01-01T00:00:00Z"),
        currentPrice = anchor,
        atmStrike = anchor,
        atmCall = 2.0,
        atmPut = 2.0,
        expectedMove = 4.0,
        expectedMovePct = 4.0,
    )

    private fun candle(ticker: String, date: String, close: Double) = StockCandleEntity(
        ticker = ticker,
        date = LocalDate.parse(date),
        open = close,
        high = close,
        low = close,
        close = close,
    )

    @Test
    fun `마지막 일봉 바로 전 거래일 종가를 그날 적용 밴드로 판정하고 근거가 없는 티커는 뺀다`() {
        // given — 2024-01-05(금) 앵커 100, 만기 01-12. 전 거래일: UP 은 01-09 종가 105(+1.25σ), FLAT 은 01-09 종가 101
        every { sigmaRepository.findByTickerInAndSnapshotDateGreaterThanEqual(any(), any()) } returns listOf(
            snapshot("UP", "2024-01-05", "2024-01-12", 100.0),
            snapshot("FLAT", "2024-01-05", "2024-01-12", 100.0),
        )
        every { candleRepository.findByTickerInAndIntervalAndDateBetween(any(), "1D", any(), any()) } returns listOf(
            candle("UP", "2024-01-08", 99.0),
            candle("UP", "2024-01-09", 105.0),
            candle("UP", "2024-01-10", 106.0),
            candle("FLAT", "2024-01-09", 101.0),
            candle("FLAT", "2024-01-10", 106.0),
            candle("NOBAND", "2024-01-09", 50.0),
        )
        val today = LocalDate.parse("2024-01-10")

        // when
        val states = service.findPreviousStates(listOf(row("UP", today), row("FLAT", today), row("NOBAND", today)))

        // then
        assertThat(states).isEqualTo(mapOf("UP" to SigmaStateEnum.UPPER_BREAK, "FLAT" to SigmaStateEnum.NORMAL))
    }
}
