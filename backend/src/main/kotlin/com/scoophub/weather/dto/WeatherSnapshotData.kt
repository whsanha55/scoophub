package com.scoophub.weather.dto

import com.scoophub.global.crawl.entity.CrawlDataEntity
import tools.jackson.databind.JsonNode

/** legacy `_weather_item` — crawl_data row → weather snapshot 응답 필드로 재구성 */
data class WeatherSnapshotData(
    val id: Long,
    val location: String?,
    val fetchedAt: String?,
    val temperature: Double?,
    val feelsLike: Double?,
    val humidity: Int?,
    val windSpeed: Double?,
    val windDirection: String?,
    val condition: String?,
    val precipMm: Double?,
    val rainChance: Int?,
    val pm10: Double?,
    val pm10Grade: String?,
    val pm25: Double?,
    val pm25Grade: String?,
    val ozone: Double?,
    val uvIndex: Double?,
    val uvGrade: String?,
    val weeklyForecast: JsonNode?,
    val rawJson: JsonNode?,
) {
    companion object {
        fun from(row: CrawlDataEntity) = with(row.response) {
            WeatherSnapshotData(
                id = row.id,
                location = scalar("location") ?: row.key,
                fetchedAt = scalar("fetched_at"),
                temperature = double("temperature"),
                feelsLike = double("feels_like"),
                humidity = int("humidity"),
                windSpeed = double("wind_speed"),
                windDirection = scalar("wind_direction"),
                condition = scalar("condition"),
                precipMm = double("precip_mm"),
                rainChance = int("rain_chance"),
                pm10 = double("pm10"),
                pm10Grade = scalar("pm10_grade"),
                pm25 = double("pm25"),
                pm25Grade = scalar("pm25_grade"),
                ozone = double("ozone"),
                uvIndex = double("uv_index"),
                uvGrade = scalar("uv_grade"),
                weeklyForecast = this["weekly_forecast"]?.takeIf { !it.isNull },
                rawJson = this["raw_json"]?.takeIf { !it.isNull },
            )
        }

        private fun JsonNode.scalar(field: String): String? = this[field]?.takeIf { !it.isNull }?.asText()

        private fun JsonNode.double(field: String): Double? = this[field]?.takeIf { !it.isNull }?.asDouble()

        private fun JsonNode.int(field: String): Int? = this[field]?.takeIf { !it.isNull }?.asInt()
    }
}
