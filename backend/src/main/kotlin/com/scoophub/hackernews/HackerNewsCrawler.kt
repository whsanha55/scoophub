package com.scoophub.hackernews

import com.scoophub.external.hackernews.HackerNewsApiClient
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.jackson.elements
import com.scoophub.global.schedule.ScheduleResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/** legacy `community/hacker_news/crawler.py` — Firebase API → crawl_data(community, hackernews) */
@Component
class HackerNewsCrawler(
    private val api: HackerNewsApiClient,
    private val store: CrawlDataStore,
    private val crawlDataRepository: CrawlDataRepository,
    private val scheduleResolver: ScheduleResolver,
    private val clock: Clock,
) : Crawler {
    override val name = "hacker_news"
    override val detail = "top_stories"

    override fun fetch(): CrawlResult {
        // legacy from_config — crawl_config 파라미터를 실행 시점에 조회
        val params = scheduleResolver.resolveParams(name)
        val maxItems = params["max_items"]?.asInt(100) ?: 100
        val minScore = params["min_score"]?.asInt(50) ?: 50
        val storyTypes = params["story_types"].elements().map { it.asText() }.ifEmpty { listOf("top", "best") }

        log.info { "hacker_news fetch started — story_types=$storyTypes max_items=$maxItems min_score=$minScore" }
        val errors = mutableListOf<String>()

        // 1) story_type별 ID 목록 조회
        val storyIds = mutableListOf<Long>()
        for (storyType in storyTypes) {
            if (storyType !in setOf("top", "best")) {
                errors.add("unknown story_type: $storyType")
                continue
            }
            try {
                storyIds += api.storyIds(storyType).elements().map { it.asLong() }
            } catch (e: Exception) {
                errors.add("${storyType}stories: ${e.message}")
                log.warn { "failed to fetch ${storyType}stories: ${e.message}" }
            }
        }
        if (storyIds.isEmpty()) {
            return CrawlResult(errors = errors)
        }

        // 2) 중복 제거 후 상위 max_items개
        val uniqueIds = storyIds.distinct().take(maxItems)

        // 3) 각 ID별 item 배치 조회 (가상 스레드 병렬)
        val itemsRaw = fetchItems(uniqueIds)

        // 4) null/deleted/dead 필터링 + min_score 필터링
        val fetchedAt = clock.instant()
        val items = itemsRaw.filterNotNull().filter { raw ->
            if (raw.isNull) {
                return@filter false
            }
            if (raw["deleted"]?.asBoolean(false) == true || raw["dead"]?.asBoolean(false) == true) {
                return@filter false
            }
            raw["score"].asInt(0) >= minScore
        }
        if (items.isEmpty()) {
            return CrawlResult(errors = errors)
        }

        // crawl_data(category=community, purpose=hackernews, key=hn_id)
        val existingIds = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn("community", "hackernews", items.map { it["id"].asText() })
            .map { it.key }
            .toSet()
        var itemsNew = 0
        for (item in items) {
            val postedAt = item["time"]?.takeIf { !it.isNull }?.let { Instant.ofEpochSecond(it.asLong()) } ?: fetchedAt
            try {
                store.upsert(
                    category = "community",
                    purpose = "hackernews",
                    key = item["id"].asText(),
                    response = mapOf(
                        "hn_id" to item["id"].asLong(),
                        "title" to item.scalarOrNull("title"),
                        "url" to item.scalarOrNull("url"),
                        "by_user" to item.scalarOrNull("by"),
                        "score" to item["score"].asInt(0),
                        "descendants" to item["descendants"]?.takeIf { !it.isNull }?.asInt(),
                        "item_type" to item.scalarOrNull("type"),
                        "body_text" to item.scalarOrNull("text"),
                        "posted_at" to postedAt.toString(),
                        "fetched_at" to fetchedAt.toString(),
                    ),
                    dateAt = postedAt,
                )
                if (item["id"].asText() !in existingIds) {
                    itemsNew++
                }
            } catch (e: Exception) {
                errors.add("item ${item["id"].asText()}: ${e.message}")
                log.warn { "upsert failed for item ${item["id"].asText()}: ${e.message}" }
            }
        }

        log.info { "hacker_news fetch completed: fetched=${items.size} new=$itemsNew errors=${errors.size}" }
        return CrawlResult(itemsFetched = items.size, itemsNew = itemsNew, errors = errors)
    }

    private fun fetchItems(ids: List<Long>): List<JsonNode?> = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
        ids.map { id -> pool.submit(Callable { runCatching { api.item(id) }.getOrNull() }) }
            .map { it.get() }
    }

    private fun JsonNode.scalarOrNull(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isMissingNode }?.asText()
}
