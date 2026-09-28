package com.scoophub.weather

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.openmeteo.OpenMeteoAirQualityClient
import com.scoophub.external.wttr.WttrClient
import com.scoophub.global.auth.JwtService
import com.scoophub.global.crawl.CrawlDataStore
import io.mockk.every
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/** legacy tests/test_api_weather.py 포팅 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WeatherControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val crawlDataStore: CrawlDataStore,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val jsonMapper = JsonMapper.builder().build()

    @MockkBean
    private lateinit var wttrClient: WttrClient

    @MockkBean
    private lateinit var airQualityClient: OpenMeteoAirQualityClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM crawl_data WHERE category = 'weather'").update()
        every { wttrClient.seoul() } returns jsonMapper.readTree(
            """
            {"current_condition": [{"temp_C": "22", "FeelsLikeC": "20", "humidity": "55",
             "windspeedKmph": "12", "winddir16Point": "SW", "weatherDesc": [{"value": "Sunny"}],
             "precipMM": "0", "chanceofrain": "0"}],
             "weather": [{"date": "2026-06-02", "maxtempC": "25", "mintempC": "18"}]}
            """.trimIndent(),
        )
        every { airQualityClient.seoulCurrent() } returns null
    }

    private fun snapshot(
        location: String = "seoul",
        temperature: Double,
        dateAt: java.time.Instant? = null,
        weekly: List<Map<String, String>>? = null,
    ) {
        val at = dateAt ?: clock.instant()
        crawlDataStore.upsert(
            "weather",
            "snapshot",
            location,
            mapOf(
                "location" to location,
                "fetched_at" to at.toString(),
                "temperature" to temperature,
                "humidity" to 55,
                "weekly_forecast" to (weekly ?: emptyList<Map<String, String>>()),
            ),
            at,
        )
    }

    @Test
    fun `데이터 없으면 data null`() {
        mockMvc.get("/api/weather").andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.meta.total") { value(0) }
            jsonPath("$.meta.returned") { value(0) }
        }
    }

    @Test
    fun `최신 스냅샷 조회`() {
        snapshot(temperature = 22.5)

        mockMvc.get("/api/weather").andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.temperature") { value(22.5) }
            jsonPath("$.data.location") { value("seoul") }
        }
    }

    @Test
    fun `신선도 필터를 벗어나면 null`() {
        // given — 60분 전 스냅샷 → 30분 창에 없음
        snapshot(temperature = 18.0, dateAt = clock.instant().minus(Duration.ofMinutes(60)))

        // when & then
        mockMvc.get("/api/weather") {
            param("minutes", "30")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value(nullValue()) }
        }
    }

    @Test
    fun `주간 예보 limit 적용`() {
        snapshot(
            temperature = 22.5,
            weekly = listOf(
                mapOf("date" to "2026-06-12", "maxtempC" to "25"),
                mapOf("date" to "2026-06-13", "maxtempC" to "24"),
            ),
        )

        mockMvc.get("/api/weather/forecast") {
            param("limit", "1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].date") { value("2026-06-12") }
        }
    }

    @Test
    fun `크롤 트리거는 super 전용`() {
        // 비로그인 → 401
        mockMvc.post("/api/crawling/weather").andExpect { status { isUnauthorized() } }

        // super → 200
        mockMvc.post("/api/crawling/weather") {
            header("Authorization", "Bearer ${jwtService.create("admin@test.com", true)}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.crawler") { value("weather") }
        }
    }
}
