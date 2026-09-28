package com.scoophub.weather

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.weather.dto.WeatherSnapshotData
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

@RestController
class WeatherController(
    private val crawlDataRepository: CrawlDataRepository,
    private val crawlRunner: CrawlRunner,
    private val crawler: WeatherCrawler,
    private val clock: Clock,
) {
    @Tag(name = "Weather")
    @Operation(summary = "현재 날씨 조회")
    @GetMapping("/api/weather")
    fun getWeather(
        @RequestParam minutes: Int? = null,
        @RequestParam("from") from: Instant? = null,
        @RequestParam to: Instant? = null,
        @RequestParam(defaultValue = "seoul") location: String = "seoul",
    ): ApiResponse<WeatherSnapshotData?> {
        log.info { "get_weather requested: location=$location minutes=$minutes" }
        // crawl_data(category=weather, purpose=snapshot, key=location).
        // 최신 스냅샷 1건 (시간 필터로 신선도 확인). 시간 파라미터가 없으면 신선도 필터 생략.
        val row = when {
            minutes != null -> crawlDataRepository.findFirstByCategoryAndPurposeAndKeyAndDateAtAfterOrderByDateAtDesc(
                "weather",
                "snapshot",
                location,
                clock.instant().minus(Duration.ofMinutes(minutes.toLong())),
            )

            from != null && to != null ->
                crawlDataRepository.findFirstByCategoryAndPurposeAndKeyAndDateAtBetweenOrderByDateAtDesc(
                    "weather",
                    "snapshot",
                    location,
                    from,
                    to,
                )

            else -> crawlDataRepository.findFirstByCategoryAndPurposeAndKeyOrderByDateAtDesc(
                "weather",
                "snapshot",
                location,
            )
        }
        return row?.let {
            ApiResponse.ok(WeatherSnapshotData.from(it), ResponseMeta(clock.instant(), total = 1, returned = 1))
        } ?: ApiResponse.ok(data = null, ResponseMeta(clock.instant(), total = 0, returned = 0))
    }

    @Tag(name = "Weather")
    @Operation(summary = "주간 날씨 예보 조회")
    @GetMapping("/api/weather/forecast")
    fun getWeatherForecast(
        @RequestParam(defaultValue = "seoul") location: String = "seoul",
        @RequestParam(defaultValue = "3") limit: Int = 3,
    ): ApiResponse<List<JsonNode>> {
        log.info { "get_weather_forecast requested: location=$location limit=$limit" }
        val row = crawlDataRepository.findLatestSnapshotWithForecast(location)
        val forecast = row?.response?.get("weekly_forecast")
            ?.takeIf { it.isArray }
            ?.toList()
            .orEmpty()
            .take(limit)
        return ApiResponse.ok(
            forecast,
            ResponseMeta(clock.instant(), total = forecast.size, returned = forecast.size),
        )
    }

    @Tag(name = "Weather Crawling")
    @Operation(summary = "Weather 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/weather")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "Weather")
}
