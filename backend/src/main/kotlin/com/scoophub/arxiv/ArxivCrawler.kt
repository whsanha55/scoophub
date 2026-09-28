package com.scoophub.arxiv

import com.scoophub.external.arxiv.ArxivClient
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

/** legacy `feed/arxiv/crawler.py` — Atom API → crawl_data(feed, arxiv) */
@Component
class ArxivCrawler(
    private val client: ArxivClient,
    private val store: CrawlDataStore,
    private val crawlDataRepository: CrawlDataRepository,
    private val scheduleResolver: ScheduleResolver,
    private val clock: Clock,
) : Crawler {
    override val name = "arxiv"
    override val detail = "daily_papers"

    override fun fetch(): CrawlResult {
        // legacy from_config — crawl_config 파라미터 (categories, max_results_per_category)
        val params = scheduleResolver.resolveParams(name)
        val categories = params["categories"].elements().map { it.asText() }
            .ifEmpty { listOf("cs.AI", "cs.LG", "cs.CL", "stat.ML") }
        val maxPerCategory = params["max_results_per_category"]?.asInt(25) ?: 25

        log.info { "arxiv fetch started — categories=$categories max=$maxPerCategory" }
        val errors = mutableListOf<String>()
        val fetchedAt = clock.instant()

        val allPapers = mutableListOf<com.scoophub.external.arxiv.ArxivPaper>()
        for (category in categories) {
            try {
                allPapers += client.searchByCategory(category, maxPerCategory)
            } catch (e: Exception) {
                errors.add("$category: ${e.message}")
                log.warn { "failed to fetch arxiv category $category: ${e.message}" }
            }
        }
        if (allPapers.isEmpty()) {
            return CrawlResult(errors = errors)
        }

        // crawl_data(category=feed, purpose=arxiv, key=arxiv_id)
        val existingIds = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn("feed", "arxiv", allPapers.map { it.arxivId })
            .map { it.key }
            .toSet()
        var itemsNew = 0
        for (paper in allPapers) {
            try {
                store.upsert(
                    category = "feed",
                    purpose = "arxiv",
                    key = paper.arxivId,
                    response = mapOf(
                        "arxiv_id" to paper.arxivId,
                        "title" to paper.title,
                        "authors" to paper.authors,
                        "summary" to paper.summary,
                        "primary_category" to paper.primaryCategory,
                        "categories" to paper.categories,
                        "pdf_url" to paper.pdfUrl,
                        "abstract_url" to paper.entryId,
                        "published_at" to paper.published?.toString(),
                        "updated_at" to paper.updated?.toString(),
                        "author_comment" to paper.authorComment,
                        "journal_ref" to paper.journalRef,
                        "fetched_at" to fetchedAt.toString(),
                    ),
                    dateAt = paper.published ?: fetchedAt,
                )
                if (paper.arxivId !in existingIds) {
                    itemsNew++
                }
            } catch (e: Exception) {
                errors.add("${paper.arxivId}: ${e.message}")
                log.warn { "upsert failed for ${paper.arxivId}: ${e.message}" }
            }
        }

        log.info { "arxiv fetch completed: fetched=${allPapers.size} new=$itemsNew errors=${errors.size}" }
        return CrawlResult(itemsFetched = allPapers.size, itemsNew = itemsNew, errors = errors)
    }
}
