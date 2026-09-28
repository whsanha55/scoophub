package com.scoophub.system

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.schedule.repository.CrawlConfigRepository
import com.scoophub.system.dto.ConfigItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `system/config_router.py` — crawl_config 관리 API */
@RestController
class SystemConfigController(
    private val configRepository: CrawlConfigRepository,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    data class ConfigPatchRequest(val params: ObjectNode)

    @Tag(name = "Crawler Config")
    @Operation(summary = "전체 crawler config 조회")
    @GetMapping("/api/config")
    fun listConfigs(): ApiResponse<List<ConfigItem>> {
        val items = configRepository.findAll().sortedBy { it.crawler }.map { ConfigItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Crawler Config")
    @Operation(summary = "단일 crawler config 조회")
    @GetMapping("/api/config/{crawler}")
    fun getConfig(@PathVariable crawler: String): ApiResponse<ConfigItem> {
        if (crawler !in PARAM_KEYS) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown crawler: '$crawler'")
        }
        val row = configRepository.findByCrawler(crawler)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "crawl_config row not found: '$crawler'")
        return ApiResponse.ok(ConfigItem.from(row), ResponseMeta(clock.instant()))
    }

    /**
     * params 부분 병합. 지원하지 않는 키는 422.
     * live reload: 도메인 크롤러가 fetch 시점에 params 를 다시 읽으므로 DB 갱신이 즉시 반영된다
     * (legacy 의 scheduler.modify_job 불필요).
     */
    @Tag(name = "Crawler Config")
    @Operation(summary = "crawler params 갱신 (런타임 반영)")
    @SuperOnly
    @PatchMapping("/api/config/{crawler}")
    fun updateConfig(@PathVariable crawler: String, @RequestBody body: ConfigPatchRequest): ApiResponse<ConfigItem> {
        val allowed = PARAM_KEYS[crawler]
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown crawler: '$crawler'")

        val unknown = body.params.propertyNames().asSequence().toSet() - allowed
        if (unknown.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unknown params for '$crawler': ${unknown.sorted()} (allowed: ${allowed.sorted()})",
            )
        }
        if (body.params.isEmpty) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "no updatable params provided")
        }
        if (configRepository.findByCrawler(crawler) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "crawl_config row not found: '$crawler'")
        }

        // JSONB 병합 (최상위 키 단위)
        jdbcClient.sql(
            "UPDATE crawl_config SET params = params || CAST(:patch AS jsonb), updated_at = now() WHERE crawler = :crawler",
        )
            .param("patch", body.params.toString())
            .param("crawler", crawler)
            .update()
        log.info { "updated crawl_config (crawler=$crawler) — live 반영은 fetch 시점 재조회" }

        val row = configRepository.findByCrawler(crawler)
        return ApiResponse.ok(ConfigItem.from(row!!), ResponseMeta(clock.instant()))
    }

    companion object {
        /** legacy PARAM_MODELS 허용 키 (news/reddit 는 도메인 이관 시점에 검증 강화) */
        private val PARAM_KEYS: Map<String, Set<String>> = mapOf(
            "news" to setOf("max_lookback_hours", "dedup_window_hours"),
            "github_trending" to setOf("since", "language", "max_repos"),
            "hacker_news" to setOf("max_items", "min_score", "story_types"),
            "arxiv" to setOf("categories", "max_results_per_category"),
            "product_hunt" to setOf("developer_token", "max_posts"),
            "youtube_trending" to setOf("api_key", "region_codes", "max_results_per_region"),
            "devto_hashnode" to setOf("tags", "max_articles_per_tag"),
            "tech_newsletter" to setOf("feeds"),
        )
    }
}
