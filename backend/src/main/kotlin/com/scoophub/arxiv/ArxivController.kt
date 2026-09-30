package com.scoophub.arxiv

import com.scoophub.arxiv.dto.ArxivItem
import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.global.crawl.service.LatestBatchService
import com.scoophub.global.crawl.vo.BatchFilter
import com.scoophub.global.crawl.vo.BatchSortKey
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
class ArxivController(
    private val batchService: LatestBatchService,
    private val crawlRunner: CrawlRunner,
    private val crawler: ArxivCrawler,
    private val clock: Clock,
) {
    @Tag(name = "arXiv")
    @Operation(summary = "arXiv 논문 조회")
    @GetMapping("/api/arxiv")
    fun getArxiv(
        @RequestParam(defaultValue = "25") limit: Int = 25,
        category: String? = null,
        since: Instant? = null,
        query: String? = null,
    ): ApiResponse<List<ArxivItem>> {
        log.info { "get_arxiv requested: category=$category since=$since query=$query limit=$limit" }
        // crawl_data(category=feed, purpose=arxiv) 최신 배치
        val filters = buildList {
            category?.let { add(BatchFilter.TextEq("primary_category", it)) }
            since?.let { add(BatchFilter.TimestamptzGte("published_at", it)) }
            query?.let { add(BatchFilter.TextLike("title", "%$it%")) }
        }
        val items = batchService.findLatest(
            "feed",
            "arxiv",
            filters,
            BatchSortKey.DateAtDesc,
            limit,
        )
            .map { ArxivItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size, returned = items.size))
    }

    @Tag(name = "arXiv Crawling")
    @Operation(summary = "arXiv 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/arxiv")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "arXiv")
}
