package com.scoophub.external.openmeteo

import com.scoophub.global.jackson.elements
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 서울 좌표 고정 — legacy crawler 파라미터와 동일 */
private const val SEOUL_QUERY =
    "?latitude=37.5665&longitude=126.9780&hourly=pm10,pm2_5,ozone,uv_index&timezone=Asia%2FSeoul"

/** Open-Meteo 시간별 대기질 중 현재 시각 값 */
data class AirQuality(val pm10: Double?, val pm25: Double?, val ozone: Double?, val uvIndex: Double?)

/** Open-Meteo Air Quality API — PM10/PM2.5/오존/자외선 */
@Component
class OpenMeteoAirQualityClient(restClientBuilder: RestClient.Builder, private val clock: Clock) {
    private val restClient = restClientBuilder
        .baseUrl("https://air-quality-api.open-meteo.com/v1/air-quality")
        .build()

    fun seoulCurrent(): AirQuality? {
        val data = restClient.get()
            .uri(SEOUL_QUERY)
            .retrieve()
            .body(JsonNode::class.java)
            ?: return null

        val hourly = data["hourly"] ?: return null
        val times = hourly["time"].elements()
        if (times.isEmpty()) {
            return null
        }

        // API 요청 timezone=Asia/Seoul → times 는 KST. now 도 KST 로 매칭.
        val nowStr = clock.instant().atZone(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val idx = times.indexOfFirst { it.asText() == nowStr }
            .takeIf { it >= 0 }
            ?: times.lastIndex // 못 찾으면 마지막 값

        fun at(field: String): Double? {
            val list = hourly[field].elements()
            return list.getOrNull(idx)?.takeIf { !it.isNull }?.asDouble()
        }
        return AirQuality(
            pm10 = at("pm10"),
            pm25 = at("pm2_5"),
            ozone = at("ozone"),
            uvIndex = at("uv_index"),
        )
    }

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
    }
}
