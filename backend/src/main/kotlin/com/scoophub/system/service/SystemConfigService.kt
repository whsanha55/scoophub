package com.scoophub.system.service

import com.scoophub.global.schedule.entity.CrawlConfigEntity
import com.scoophub.global.schedule.repository.CrawlConfigQueryRepository
import com.scoophub.global.schedule.repository.CrawlConfigRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.node.ObjectNode

private val log = KotlinLogging.logger {}

@Service
class SystemConfigService(
    private val configRepository: CrawlConfigRepository,
    private val configQueryRepository: CrawlConfigQueryRepository,
) {
    fun findAll(): List<CrawlConfigEntity> = configRepository.findAll().sortedBy { it.crawler }

    fun find(crawler: String): CrawlConfigEntity {
        if (crawler !in PARAM_KEYS) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown crawler: '$crawler'")
        }
        return findRow(crawler)
    }

    /**
     * params 부분 병합. 지원하지 않는 키는 422.
     * live reload: 도메인 크롤러가 fetch 시점에 params 를 다시 읽으므로 DB 갱신이 즉시 반영된다
     * (legacy 의 scheduler.modify_job 불필요).
     */
    fun updateParams(crawler: String, params: ObjectNode): CrawlConfigEntity {
        val allowed = PARAM_KEYS[crawler]
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown crawler: '$crawler'")

        val unknown = params.propertyNames().asSequence().toSet() - allowed
        if (unknown.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unknown params for '$crawler': ${unknown.sorted()} (allowed: ${allowed.sorted()})",
            )
        }
        if (params.isEmpty) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "no updatable params provided")
        }
        findRow(crawler)

        configQueryRepository.mergeParams(crawler, params.toString())
        log.info { "updated crawl_config (crawler=$crawler) — live 반영은 fetch 시점 재조회" }
        return findRow(crawler)
    }

    private fun findRow(crawler: String): CrawlConfigEntity = configRepository.findByCrawler(crawler)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "crawl_config row not found: '$crawler'")

    companion object {
        /** legacy PARAM_MODELS 허용 키 (news 는 도메인 이관 시점에 검증 강화) */
        private val PARAM_KEYS: Map<String, Set<String>> = mapOf(
            "news" to setOf("max_lookback_hours", "dedup_window_hours"),
            "github_trending" to setOf("since", "language", "max_repos"),
            "hacker_news" to setOf("max_items", "min_score", "story_types"),
            "arxiv" to setOf("categories", "max_results_per_category"),
            "youtube_trending" to setOf("api_key", "region_codes", "max_results_per_region"),
            "devto_hashnode" to setOf("tags", "max_articles_per_tag"),
            "tech_newsletter" to setOf("feeds"),
        )
    }
}
