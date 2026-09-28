package com.scoophub.producthunt

import com.scoophub.external.producthunt.ProductHuntClient
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.jackson.elements
import com.scoophub.global.schedule.ScheduleResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

/** legacy `community/product_hunt/crawler.py` — GraphQL → crawl_data(community, producthunt) */
@Component
class ProductHuntCrawler(
    private val client: ProductHuntClient,
    private val store: CrawlDataStore,
    private val crawlDataRepository: CrawlDataRepository,
    private val scheduleResolver: ScheduleResolver,
    private val clock: Clock,
) : Crawler {
    override val name = "product_hunt"
    override val detail = "daily_launches"

    override fun fetch(): CrawlResult {
        // legacy from_config(yaml) — developer_token 은 env(properties), max_posts 는 crawl_config 와 동일값
        val params = scheduleResolver.resolveParams(name)
        val maxPosts = params["max_posts"]?.asInt(30) ?: 30

        log.info { "product_hunt fetch started — max_posts=$maxPosts" }
        val fetchedAt = clock.instant()

        val edges = try {
            client.todayPosts(maxPosts)["data"]["posts"]["edges"].elements()
        } catch (e: Exception) {
            log.error(e) { "Product Hunt API failed: ${e.message}" }
            return CrawlResult(errors = listOf(e.message ?: e.toString()))
        }
        if (edges.isEmpty()) {
            return CrawlResult()
        }

        // crawl_data(category=community, purpose=producthunt, key=ph_id)
        val nodes = edges.mapNotNull { it.get("node")?.takeIf { node -> !node.isNull } }
        val existingIds = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn("community", "producthunt", nodes.map { it["id"].asText() })
            .map { it.key }
            .toSet()
        var itemsNew = 0
        val errors = mutableListOf<String>()
        for (node in nodes) {
            try {
                val topics = node["topics"]["edges"].elements().map { it["node"]["name"].asText() }
                val postedAt = node.scalar("createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: fetchedAt
                val featuredAt = node.scalar("featuredAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }

                store.upsert(
                    category = "community",
                    purpose = "producthunt",
                    key = node["id"].asText(),
                    response = mapOf(
                        "ph_id" to node["id"].asText(),
                        "name" to (node.scalar("name") ?: ""),
                        "tagline" to node.scalar("tagline"),
                        "slug" to (node.scalar("slug") ?: ""),
                        "ph_url" to (node.scalar("url") ?: ""),
                        "website_url" to node.scalar("website"),
                        "votes_count" to node["votesCount"].asInt(0),
                        "comments_count" to node["commentsCount"].asInt(0),
                        "topics" to topics,
                        "featured_at" to featuredAt?.toString(),
                        "posted_at" to postedAt.toString(),
                        "fetched_at" to fetchedAt.toString(),
                    ),
                    dateAt = postedAt,
                )
                if (node["id"].asText() !in existingIds) {
                    itemsNew++
                }
            } catch (e: Exception) {
                errors.add("${node.scalar("id") ?: "?"}: ${e.message}")
                log.warn { "upsert failed: ${e.message}" }
            }
        }

        log.info { "product_hunt fetch completed: fetched=${edges.size} new=$itemsNew errors=${errors.size}" }
        return CrawlResult(itemsFetched = edges.size, itemsNew = itemsNew, errors = errors)
    }

    private fun tools.jackson.databind.JsonNode.scalar(field: String): String? =
        this[field]?.takeIf { !it.isNull && !it.isMissingNode }?.asText()
}
