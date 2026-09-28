package com.scoophub.hackernews

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.LatestBatchQuery
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.hackernews.dto.HackerNewsItem
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
class HackerNewsController(
    private val batchQuery: LatestBatchQuery,
    private val crawlRunner: CrawlRunner,
    private val crawler: HackerNewsCrawler,
    private val clock: Clock,
) {
    @Tag(name = "Hacker News")
    @Operation(summary = "Hacker News 아이템 조회")
    @GetMapping("/api/hacker-news")
    fun getHackerNews(
        @RequestParam(defaultValue = "25") limit: Int = 25,
        @RequestParam("min_score") minScore: Int? = null,
        @RequestParam(name = "item_type", defaultValue = "story") itemType: String = "story",
        since: Instant? = null,
    ): ApiResponse<List<HackerNewsItem>> {
        log.info { "get_hacker_news requested: limit=$limit min_score=$minScore item_type=$itemType since=$since" }
        // crawl_data(category=community, purpose=hackernews) 최신 배치
        val latest = batchQuery.latestFetchedAt("community", "hackernews")
            ?: return empty()
        val filters = buildList {
            add(LatestBatchQuery.TextEq("item_type", itemType))
            minScore?.let { add(LatestBatchQuery.IntGte("score", it)) }
            since?.let { add(LatestBatchQuery.TimestamptzGte("posted_at", it)) }
        }
        val items = batchQuery.fetch(
            "community",
            "hackernews",
            latest,
            filters,
            LatestBatchQuery.SortKey.IntDesc("score"),
            limit,
        )
            .map { HackerNewsItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size, returned = items.size))
    }

    @Tag(name = "Hacker News Crawling")
    @Operation(summary = "Hacker News 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/hacker-news")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "Hacker News")

    private fun empty() = ApiResponse.ok(
        emptyList<HackerNewsItem>(),
        ResponseMeta(clock.instant(), total = 0, returned = 0),
    )
}
