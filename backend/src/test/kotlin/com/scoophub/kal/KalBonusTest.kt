package com.scoophub.kal

import com.scoophub.TestcontainersConfiguration
import com.scoophub.global.crawl.CrawlDataStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** legacy tests/test_kal_bonus.py + test_api_kal_bonus.py 포팅 (Playwright 구동 제외) */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class KalBonusTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val jsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'kal'").update()
    }

    @Test
    fun `parse_bonus_response — cabin_label 부여 구조화`() {
        // given
        val raw = jsonMapper.readTree(
            """
            {"departureAirport": "ICN", "arrivalAirport": "LHR",
             "flightList": [
               {"departureDate": "20270101", "flightDetailList": [
                 {"flightNumber": "KE907", "departureTime": "10:40", "frontBookingClass": "P", "availableSeat": "2"},
                 {"flightNumber": "KE908", "departureTime": "22:10", "frontBookingClass": "E", "availableSeat": "0"},
                 {"flightNumber": "KE909", "departureTime": "07:05", "frontBookingClass": "Z", "availableSeat": 5}
               ]}
             ]}
            """,
        )

        // when
        val parsed = KalBonusResponseParser.parse(raw)

        // then
        assertThat(parsed.departure).isEqualTo("ICN")
        assertThat(parsed.arrival).isEqualTo("LHR")
        val flights = parsed.days.single().flights
        assertThat(flights[0].cabinLabel).isEqualTo("프레스티지석 보너스")
        assertThat(flights[0].available).isTrue()
        assertThat(flights[1].cabinLabel).isEqualTo("일반석 보너스")
        assertThat(flights[1].available).isFalse() // "0" 오탐 방지
        assertThat(flights[2].cabinLabel).isEqualTo("Z") // 미정의 코드 원문
        assertThat(flights[2].available).isTrue()
    }

    @Test
    fun `make_key 와 monthFirstDay`() {
        assertThat(KalConfig.makeKey("ICN", "LHR", "202701")).isEqualTo("202701-ICN-LHR")
        assertThat(KalConfig.monthFirstDay("202702")).isEqualTo("20270201")
    }

    @Test
    fun `month·arrival 생략 시 월 목록 메타`() {
        // given
        upsertKal("202701-ICN-LHR")
        upsertKal("202702-ICN-FRA")

        // when & then
        mockMvc.get("/api/kal-bonus").andExpect {
            jsonPath("$.data.length()") { value(0) }
            jsonPath("$.meta.months.length()") { value(2) }
            jsonPath("$.meta.months[0]") { value("202701") }
        }
    }

    @Test
    fun `month·arrival 필터 조회`() {
        // given
        upsertKal("202701-ICN-LHR")
        upsertKal("202701-ICN-FRA")
        upsertKal("202702-ICN-LHR")

        // when & then — 월 필터
        mockMvc.get("/api/kal-bonus") { param("month", "202701") }.andExpect {
            jsonPath("$.data.length()") { value(2) }
        }

        // 월 + 도착지
        mockMvc.get("/api/kal-bonus") {
            param("month", "202701")
            param("arrival", "lhr")
        }.andExpect {
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].key") { value("202701-ICN-LHR") }
            jsonPath("$.data[0].parsed.departure") { value("ICN") }
            jsonPath("$.data[0].parsed.days.length()") { value(1) }
        }
    }

    private fun upsertKal(key: String) {
        crawlDataStore.upsert(
            KalConfig.CATEGORY,
            KalConfig.PURPOSE,
            key,
            mapOf(
                "departureAirport" to "ICN",
                "arrivalAirport" to key.substringAfterLast("-"),
                "flightList" to listOf(mapOf("departureDate" to "20270101")),
            ),
            clock.instant(),
        )
    }
}

/** cabin 라벨 매핑 — 순수 단위 */
class KalCabinTest {
    @Test
    fun `frontBookingClass → cabin_label`() {
        assertThat(KalCabin.map("E")).isEqualTo("일반석 보너스")
        assertThat(KalCabin.map("P")).isEqualTo("프레스티지석 보너스")
        assertThat(KalCabin.map("Ø")).isEqualTo("운항편 없음")
        assertThat(KalCabin.map("Z")).isEqualTo("Z")
        assertThat(KalCabin.map(null)).isEmpty()
        assertThat(KalCabin.map("")).isEmpty()
    }
}
