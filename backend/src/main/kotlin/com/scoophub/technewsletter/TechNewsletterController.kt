package com.scoophub.technewsletter

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.LatestBatchQuery
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.technewsletter.dto.NewsletterItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

@RestController
class TechNewsletterController(
    private val batchQuery: LatestBatchQuery,
    private val crawlRunner: CrawlRunner,
    private val crawler: TechNewsletterCrawler,
    private val clock: Clock,
) {
    @Tag(name = "Tech Newsletter")
    @Operation(summary = "Tech Newsletter 아티클 조회")
    @GetMapping("/api/tech-newsletter")
    fun getTechNewsletter(
        @RequestParam(defaultValue = "25") limit: Int = 25,
        source: String? = null,
        since: Instant? = null,
    ): ApiResponse<List<NewsletterItem>> {
        log.info { "get_tech_newsletter requested: limit=$limit source=$source since=$since" }
        // crawl_data(category=feed, purpose=newsletter) 최신 배치
        val latest = batchQuery.latestFetchedAt("feed", "newsletter") ?: return empty()
        val filters = buildList {
            source?.let { add(LatestBatchQuery.TextEq("source", it)) }
            since?.let { add(LatestBatchQuery.TimestamptzGte("published_at", it)) }
        }
        val items = batchQuery.fetch("feed", "newsletter", latest, filters, LatestBatchQuery.SortKey.DateAtDesc, limit)
            .map { NewsletterItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size, returned = items.size))
    }

    @Tag(name = "Tech Newsletter Crawling")
    @Operation(summary = "Tech Newsletter 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/tech-newsletter")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "Tech Newsletter")

    private fun empty() = ApiResponse.ok(
        emptyList<NewsletterItem>(),
        ResponseMeta(clock.instant(), total = 0, returned = 0),
    )
}
