package com.scoophub.devto

import com.scoophub.external.devto.DevtoClient
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

private val log = KotlinLogging.logger {}

/** legacy `feed/devto_hashnode/crawler.py` — Dev.to 태그별 아티클 → crawl_data(feed, devblog) */
@Component
class DevtoHashnodeCrawler(
    private val client: DevtoClient,
    private val store: CrawlDataStore,
    private val crawlDataRepository: CrawlDataRepository,
    private val scheduleResolver: ScheduleResolver,
    private val clock: Clock,
) : Crawler {
    override val name = "devto_hashnode"
    override val detail = "trending_articles"

    override fun fetch(): CrawlResult {
        // legacy from_config — crawl_config 파라미터 (tags, max_articles_per_tag)
        val params = scheduleResolver.resolveParams(name)
        val tags = params["tags"].elements().map { it.asText() }
            .ifEmpty { listOf("python", "javascript", "webdev", "tutorial", "beginners") }
        val maxPerTag = params["max_articles_per_tag"]?.asInt(30) ?: 30

        log.info { "devto_hashnode fetch started — tags=$tags" }
        val errors = mutableListOf<String>()
        val fetchedAt = clock.instant()

        val allItems = mutableListOf<JsonNode>()
        for (tag in tags) {
            try {
                allItems += client.articlesByTag(tag, perPage = maxPerTag).elements()
            } catch (e: Exception) {
                errors.add("$tag: ${e.message}")
                log.warn { "failed to fetch devto tag $tag: ${e.message}" }
            }
        }
        if (allItems.isEmpty()) {
            return CrawlResult(errors = errors)
        }

        // article_id로 중복 제거 (여러 태그에 같은 글이 있을 수 있음)
        val unique = allItems.distinctBy { it["id"]?.asLong() }
        val existingIds = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn("feed", "devblog", unique.map { it["id"].asText() })
            .map { it.key }
            .toSet()
        var itemsNew = 0
        for (item in unique) {
            try {
                val publishedAt = item.scalar("published_at")?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: fetchedAt
                store.upsert(
                    category = "feed",
                    purpose = "devblog",
                    key = item["id"].asText(),
                    response = mapOf(
                        "article_id" to item["id"].asLong(),
                        "title" to (item.scalar("title") ?: ""),
                        "url" to (item.scalar("url") ?: ""),
                        "author" to (
                            item["user"]?.get("name")?.takeIf { !it.isNull }?.asText()
                                ?: item["user"]?.get("username")?.takeIf { !it.isNull }?.asText()
                            ),
                        "description" to item.scalar("description"),
                        "reactions_count" to item["public_reactions_count"].asInt(0),
                        "comments_count" to item["comments_count"].asInt(0),
                        "reading_time" to item["reading_time"]?.takeIf { !it.isNull }?.asInt(),
                        "tags" to item["tag_list"].elements().map { it.asText() },
                        "source" to "devto",
                        "published_at" to publishedAt.toString(),
                        "fetched_at" to fetchedAt.toString(),
                    ),
                    dateAt = publishedAt,
                )
                if (item["id"].asText() !in existingIds) {
                    itemsNew++
                }
            } catch (e: Exception) {
                errors.add("${item["id"].asText()}: ${e.message}")
                log.warn { "upsert failed for ${item["id"].asText()}: ${e.message}" }
            }
        }

        log.info { "devto_hashnode fetch completed: fetched=${unique.size} new=$itemsNew errors=${errors.size}" }
        return CrawlResult(itemsFetched = unique.size, itemsNew = itemsNew, errors = errors)
    }

    private fun JsonNode.scalar(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isMissingNode }?.asText()
}
