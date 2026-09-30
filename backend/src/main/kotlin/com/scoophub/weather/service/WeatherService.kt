package com.scoophub.weather.service

import com.scoophub.global.crawl.entity.CrawlDataEntity
import com.scoophub.global.crawl.repository.CrawlDataRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class WeatherService(private val crawlDataRepository: CrawlDataRepository, private val clock: Clock) {
    /**
     * crawl_data(category=weather, purpose=snapshot, key=location).
     * 최신 스냅샷 1건 (시간 필터로 신선도 확인). 시간 파라미터가 없으면 신선도 필터 생략.
     */
    fun findSnapshot(location: String, minutes: Int?, from: Instant?, to: Instant?): CrawlDataEntity? = when {
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

    /** weekly_forecast 가 비지 않은 최신 스냅샷의 예보 limit 건 */
    fun findForecast(location: String, limit: Int): List<JsonNode> =
        crawlDataRepository.findLatestSnapshotWithForecast(location)
            ?.response?.get("weekly_forecast")
            ?.takeIf { it.isArray }
            ?.toList()
            .orEmpty()
            .take(limit)
}
