package com.scoophub.weather

import com.scoophub.external.openmeteo.AirQuality
import com.scoophub.external.openmeteo.OpenMeteoAirQualityClient
import com.scoophub.external.wttr.WttrClient
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `weather/crawler.py` — wttr.in + Open-Meteo → crawl_data snapshot upsert */
@Component
class WeatherCrawler(
    private val wttrClient: WttrClient,
    private val airQualityClient: OpenMeteoAirQualityClient,
    private val store: CrawlDataStore,
    private val clock: Clock,
) : Crawler {
    override val name = "weather"
    override val detail = "forecast"

    override fun fetch(): CrawlResult {
        log.info { "weather fetch started" }
        val errors = mutableListOf<String>()

        var wttrData: JsonNode? = null
        var air: AirQuality? = null
        try {
            wttrData = wttrClient.seoul()
        } catch (e: Exception) {
            errors.add("wttr.in: ${e.message}")
            log.warn { "wttr.in fetch failed: ${e.message}" }
        }
        try {
            air = airQualityClient.seoulCurrent()
        } catch (e: Exception) {
            errors.add("Open-Meteo: ${e.message}")
            log.warn { "Open-Meteo fetch failed: ${e.message}" }
        }

        // wttr.in 실패 시 저장할 스냅샷 자체가 없음 (Open-Meteo 단독은 불완전)
        val data = wttrData ?: return CrawlResult(errors = errors)

        // wttr.in 현재 날씨 파싱
        val cc = requireNotNull(data["current_condition"]?.get(0)) { "wttr.in current_condition missing" }
        val conditionEn = cc["weatherDesc"]?.get(0)?.get("value")?.asText("") ?: ""

        val pm10 = air?.pm10
        val pm25 = air?.pm25
        val uvIndex = air?.uvIndex

        // wttr.in 주간 예보 (최대 3일치)
        val weekly = data["weather"]?.takeIf { it.isArray }?.toList().orEmpty().take(3)

        // crawl_data(category=weather, purpose=snapshot, key=location).
        // 동일 location 재크롤 = upsert(최신 덮어쓰기). 과거 스냅샷 히스토리는 손실(사용자 확정).
        val fetchedAt = clock.instant()
        store.upsert(
            category = "weather",
            purpose = "snapshot",
            key = "seoul",
            response = linkedMapOf(
                "location" to "seoul",
                "fetched_at" to fetchedAt.toString(),
                "temperature" to cc["temp_C"].asDouble(0.0),
                "feels_like" to cc["FeelsLikeC"].asDouble(0.0),
                "humidity" to cc["humidity"].asInt(0),
                "wind_speed" to cc["windspeedKmph"].asDouble(0.0),
                "wind_direction" to cc["winddir16Point"].asText(""),
                "condition" to translateCondition(conditionEn),
                "precip_mm" to cc["precipMM"].asDouble(0.0),
                "rain_chance" to cc["chanceofrain"].asInt(0),
                "pm10" to pm10,
                "pm10_grade" to grade(pm10, PM10_THRESHOLDS),
                "pm25" to pm25,
                "pm25_grade" to grade(pm25, PM25_THRESHOLDS),
                "ozone" to air?.ozone,
                "uv_index" to uvIndex,
                "uv_grade" to grade(uvIndex, UV_THRESHOLDS),
                "weekly_forecast" to weekly,
                "raw_json" to data,
            ),
            dateAt = fetchedAt,
        )

        log.info { "weather fetch completed: errors=${errors.size}" }
        return CrawlResult(itemsFetched = 1, itemsNew = 1, errors = errors)
    }

    companion object {
        private val WEATHER_KO_MAP = mapOf(
            "clear" to "맑음",
            "sunny" to "맑음",
            "partly cloudy" to "구름 조금",
            "cloudy" to "흐림",
            "overcast" to "흐림",
            "light rain" to "가벼운 비",
            "moderate rain" to "비",
            "heavy rain" to "폭우",
            "light snow" to "가벼운 눈",
            "moderate snow" to "눈",
            "heavy snow" to "폭설",
            "fog" to "안개",
            "mist" to "박무",
            "thunderstorm" to "뇌우",
        )

        private val PM10_THRESHOLDS = listOf(30.0 to "좋음", 80.0 to "보통", 150.0 to "나쁨", 999.0 to "매우나쁨")
        private val PM25_THRESHOLDS = listOf(15.0 to "좋음", 35.0 to "보통", 75.0 to "나쁨", 999.0 to "매우나쁨")
        private val UV_THRESHOLDS = listOf(2.0 to "낮음", 5.0 to "보통", 7.0 to "높음", 10.0 to "매우높음", 99.0 to "위험")

        fun grade(value: Double?, thresholds: List<Pair<Double, String>>): String? {
            if (value == null) {
                return null
            }
            return thresholds.firstOrNull { value <= it.first }?.second ?: thresholds.last().second
        }

        fun translateCondition(english: String): String = WEATHER_KO_MAP[english.lowercase().trim()] ?: english
    }
}
