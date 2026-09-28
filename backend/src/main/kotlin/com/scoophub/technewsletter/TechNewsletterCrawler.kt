package com.scoophub.technewsletter

import com.scoophub.external.rss.RssClient
import com.scoophub.external.rss.RssEntry
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.jackson.elements
import com.scoophub.global.schedule.ScheduleResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `feed/tech_newsletter/crawler.py` — 뉴스레터 RSS → crawl_data(feed, newsletter) */
@Component
class TechNewsletterCrawler(
    private val rssClient: RssClient,
    private val store: CrawlDataStore,
    private val crawlDataRepository: CrawlDataRepository,
    private val scheduleResolver: ScheduleResolver,
    private val clock: Clock,
) : Crawler {
    override val name = "tech_newsletter"
    override val detail = "rss_feeds"

    data class Feed(val url: String, val source: String)

    override fun fetch(): CrawlResult {
        // legacy from_config(yaml) 과 스케줄러(crawl_config) 소스가 다르지만 값은 동일 — crawl_config 단일화
        val params = scheduleResolver.resolveParams(name)
        val feeds = params["feeds"].elements().mapNotNull { node ->
            val url = node.get("url")?.takeIf { !it.isNull }?.asText() ?: return@mapNotNull null
            val source = node.get("source")?.takeIf { !it.isNull }?.asText() ?: ""
            Feed(url, source)
        }.ifEmpty {
            listOf(
                Feed("https://tldr.tech/api/rss/tech", "TLDR Tech"),
                Feed("https://tldr.tech/api/rss/ai", "TLDR AI"),
                Feed("https://techcrunch.com/feed/", "TechCrunch"),
                Feed("https://www.theverge.com/rss/tech/index.xml", "The Verge"),
            )
        }

        log.info { "tech_newsletter fetch started — ${feeds.size} feeds" }
        val errors = mutableListOf<String>()
        val fetchedAt = clock.instant()

        data class EntryWithSource(val entry: RssEntry, val source: String)

        val allEntries = mutableListOf<EntryWithSource>()
        for (feed in feeds) {
            try {
                rssClient.fetch(feed.url).forEach { allEntries += EntryWithSource(it, feed.source) }
            } catch (e: Exception) {
                errors.add("${feed.source}: ${e.message}")
                log.warn { "failed to parse feed ${feed.url}: ${e.message}" }
            }
        }
        if (allEntries.isEmpty()) {
            return CrawlResult(errors = errors)
        }

        // crawl_data(category=feed, purpose=newsletter, key=url)
        val withUrl = allEntries.filter { it.entry.link.isNotEmpty() }
        val existingUrls = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn("feed", "newsletter", withUrl.map { it.entry.link })
            .map { it.key }
            .toSet()
        var itemsNew = 0
        for (e in withUrl) {
            try {
                store.upsert(
                    category = "feed",
                    purpose = "newsletter",
                    key = e.entry.link,
                    response = mapOf(
                        "title" to e.entry.title,
                        "source" to e.source,
                        "summary" to e.entry.summary,
                        "author" to e.entry.author,
                        "category" to e.entry.category,
                        "published_at" to (e.entry.published ?: fetchedAt).toString(),
                        "fetched_at" to fetchedAt.toString(),
                    ),
                    dateAt = e.entry.published ?: fetchedAt,
                )
                if (e.entry.link !in existingUrls) {
                    itemsNew++
                }
            } catch (ex: Exception) {
                errors.add("${e.entry.link}: ${ex.message}")
                log.warn { "upsert failed: ${ex.message}" }
            }
        }

        log.info { "tech_newsletter fetch completed: fetched=${allEntries.size} new=$itemsNew errors=${errors.size}" }
        return CrawlResult(itemsFetched = allEntries.size, itemsNew = itemsNew, errors = errors)
    }
}
