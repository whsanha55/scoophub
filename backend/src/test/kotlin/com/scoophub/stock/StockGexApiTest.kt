package com.scoophub.stock

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.stock.repository.StockGexQueryRepository
import com.scoophub.stock.vo.GexStrike
import com.scoophub.stock.vo.StockGexRow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.LocalDate

/** GET /api/stock/gex/{ticker} — 최신 스냅샷을 snake_case 로 돌려준다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StockGexApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val gexRepository: StockGexQueryRepository,
    private val jdbcClient: JdbcClient,
) {
    @MockkBean
    private lateinit var provider: AlpacaMarketDataClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM stock_gex").update()
    }

    private fun row(date: String, spot: Double) = StockGexRow(
        ticker = "SOXX",
        snapshotDate = LocalDate.parse(date),
        snapshotAt = Instant.parse("${date}T21:30:00Z"),
        oiDate = LocalDate.parse(date),
        spot = spot,
        netGex = -46_300.0,
        gammaFlip = 561.25,
        resistances = listOf(585.0, 580.0),
        supports = emptyList(),
        accelerations = listOf(500.0, 520.0),
        strikes = listOf(GexStrike(strike = 545.0, callGex = 100.0, putGex = -1200.0, netGex = -1100.0)),
    )

    @Test
    fun `가장 최근 스냅샷 날짜의 GEX 를 돌려준다`() {
        // given
        gexRepository.upsert(row("2026-10-09", 559.4))
        gexRepository.upsert(row("2026-10-12", 544.85))

        // when & then
        mockMvc.get("/api/stock/gex/soxx").andExpect {
            status { isOk() }
            jsonPath("$.data.ticker") { value("SOXX") }
            jsonPath("$.data.snapshot_date") { value("2026-10-12") }
            jsonPath("$.data.oi_date") { value("2026-10-12") }
            jsonPath("$.data.spot") { value(544.85) }
            jsonPath("$.data.net_gex") { value(-46_300.0) }
            jsonPath("$.data.gamma_flip") { value(561.25) }
            jsonPath("$.data.resistances[0]") { value(585.0) }
            jsonPath("$.data.supports.length()") { value(0) }
            jsonPath("$.data.strikes[0].net_gex") { value(-1100.0) }
            jsonPath("$.data.strikes[0].put_gex") { value(-1200.0) }
        }
    }

    @Test
    fun `같은 날 다시 저장하면 덮어쓴다`() {
        // given
        gexRepository.upsert(row("2026-10-12", 540.0))
        gexRepository.upsert(row("2026-10-12", 544.85))

        // when & then
        mockMvc.get("/api/stock/gex/SOXX").andExpect {
            jsonPath("$.data.spot") { value(544.85) }
        }
    }

    @Test
    fun `데이터가 없으면 data 가 null 이다`() {
        // when & then
        mockMvc.get("/api/stock/gex/NONE").andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data") { doesNotExist() }
        }
    }
}
