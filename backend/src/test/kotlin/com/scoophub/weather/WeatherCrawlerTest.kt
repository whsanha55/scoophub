package com.scoophub.weather

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.openmeteo.AirQuality
import com.scoophub.external.openmeteo.OpenMeteoAirQualityClient
import com.scoophub.external.wttr.WttrClient
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.jackson.scalar
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper

/** legacy tests/test_weather_crawler.py 포팅 — 외부 API 는 클라이언트 mock */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WeatherCrawlerTest @Autowired constructor(
    private val crawlRunner: CrawlRunner,
    private val crawler: WeatherCrawler,
    private val crawlDataRepository: CrawlDataRepository,
) {
    private val jsonMapper = JsonMapper.builder().build()

    @MockkBean
    private lateinit var wttrClient: WttrClient

    @MockkBean
    private lateinit var airQualityClient: OpenMeteoAirQualityClient

    @Test
    fun `크롤은 스냅샷을 저장한다`() {
        // given
        every { wttrClient.seoul() } returns jsonMapper.readTree(
            """
            {"current_condition": [{
                "temp_C": "22", "FeelsLikeC": "20", "humidity": "55",
                "windspeedKmph": "12", "winddir16Point": "SW",
                "weatherDesc": [{"value": "Light rain"}],
                "precipMM": "1.2", "chanceofrain": "60"
            }],
            "weather": [
                {"date": "2026-06-02", "maxtempC": "25", "mintempC": "18"},
                {"date": "2026-06-03", "maxtempC": "24", "mintempC": "17"}
            ]}
            """,
        )
        every { airQualityClient.seoulCurrent() } returns
            AirQuality(pm10 = 15.0, pm25 = 8.0, ozone = 45.0, uvIndex = 0.0)

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isGreaterThanOrEqualTo(1)
        val row = crawlDataRepository.findByCategoryAndPurposeAndKey("weather", "snapshot", "seoul")
        assertThat(row).isNotNull
        assertThat(row!!.response["temperature"].asDouble()).isEqualTo(22.0)
        assertThat(row.response.scalar("condition")).isEqualTo("가벼운 비")
    }

    @Test
    fun `wttr 실패 시 스냅샷 없이 에러만`() {
        // given
        every { wttrClient.seoul() } throws RuntimeException("boom")
        every { airQualityClient.seoulCurrent() } returns AirQuality(null, null, null, null)

        // when
        val result = crawlRunner.run(crawler)

        // then — legacy 와 동일: fetch 는 예외 삼키고 errors 에 기록
        assertThat(result).isNotNull
        assertThat(result!!.itemsFetched).isZero()
        assertThat(result.itemsNew).isZero()
        assertThat(result.errors.single()).contains("wttr.in")
    }
}
