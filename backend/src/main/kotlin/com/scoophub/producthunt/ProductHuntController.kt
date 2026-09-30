package com.scoophub.producthunt

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.global.crawl.service.LatestBatchService
import com.scoophub.global.crawl.vo.BatchFilter
import com.scoophub.global.crawl.vo.BatchSortKey
import com.scoophub.producthunt.dto.ProductHuntItem
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
class ProductHuntController(
    private val batchService: LatestBatchService,
    private val crawlRunner: CrawlRunner,
    private val crawler: ProductHuntCrawler,
    private val clock: Clock,
) {
    @Tag(name = "Product Hunt")
    @Operation(summary = "Product Hunt 게시물 조회")
    @GetMapping("/api/product-hunt")
    fun getProductHunt(
        @RequestParam(defaultValue = "25") limit: Int = 25,
        topic: String? = null,
        since: Instant? = null,
    ): ApiResponse<List<ProductHuntItem>> {
        log.info { "get_product_hunt requested: limit=$limit topic=$topic since=$since" }
        // crawl_data(category=community, purpose=producthunt) 최신 배치
        val filters = buildList {
            topic?.let { add(BatchFilter.JsonArrayContains("topics", """["$it"]""")) }
            since?.let { add(BatchFilter.TimestamptzGte("posted_at", it)) }
        }
        val items = batchService.findLatest(
            "community",
            "producthunt",
            filters,
            BatchSortKey.IntDesc("votes_count"),
            limit,
        ).map { ProductHuntItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size, returned = items.size))
    }

    @Tag(name = "Product Hunt Crawling")
    @Operation(summary = "Product Hunt 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/product-hunt")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "Product Hunt")
}
