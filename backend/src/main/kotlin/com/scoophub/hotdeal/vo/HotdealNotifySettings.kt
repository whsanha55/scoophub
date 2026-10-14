package com.scoophub.hotdeal.vo

import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** crawl_config('hotdeal_notify').params. 값이 없거나 잘못되면 기본값을 쓴다 */
data class HotdealNotifySettings(
    val enabled: Boolean,
    val scoreThreshold: Double,
    val dailyLimit: Int,
    val quietStart: LocalTime,
    val quietEnd: LocalTime,
    val zone: ZoneId,
    val maxAge: Duration,
    val webUrl: String,
) {
    /** 조용한 시간이면 true. start > end 면 자정을 넘기는 구간이다 (예: 23:00~08:00) */
    fun isQuiet(now: Instant): Boolean {
        val time = now.atZone(zone).toLocalTime()
        return if (quietStart <= quietEnd) {
            time >= quietStart && time < quietEnd
        } else {
            time >= quietStart || time < quietEnd
        }
    }

    /** 하루 상한을 세는 기준 시각 (설정 시간대의 오늘 0시) */
    fun dayStart(now: Instant): Instant = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    companion object {
        fun from(params: JsonNode) = HotdealNotifySettings(
            enabled = params.path("enabled").asBoolean(false),
            scoreThreshold = params.path("score_threshold").asDouble(5.0),
            dailyLimit = params.path("daily_limit").asInt(5),
            quietStart = parseTime(params.path("quiet_start").asText(""), LocalTime.of(23, 0)),
            quietEnd = parseTime(params.path("quiet_end").asText(""), LocalTime.of(8, 0)),
            zone = runCatching { ZoneId.of(params.path("timezone").asText("")) }.getOrDefault(ZoneId.of("Asia/Seoul")),
            maxAge = Duration.ofHours(params.path("max_age_hours").asLong(6)),
            webUrl = params.path("web_url").asText("").trimEnd('/'),
        )

        private fun parseTime(value: String, default: LocalTime): LocalTime =
            runCatching { LocalTime.parse(value) }.getOrDefault(default)
    }
}
