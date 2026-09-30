package com.scoophub.devto

import com.scoophub.devto.dto.DevblogItem
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
class DevtoHashnodeController(
    private val batchService: LatestBatchService,
    private val crawlRunner: CrawlRunner,
    private val crawler: DevtoHashnodeCrawler,
    private val clock: Clock,
) {
    @Tag(name = "Dev.to")
    @Operation(summary = "Dev.to 트렌딩 아티클 조회")
    @GetMapping("/api/devto-hashnode")
    fun getDevtoHashnode(
        @RequestParam(defaultValue = "25") limit: Int = 25,
        tag: String? = null,
        since: Instant? = null,
    ): ApiResponse<List<DevblogItem>> {
        log.info { "get_devto_hashnode requested: limit=$limit tag=$tag since=$since" }
        // crawl_data(category=feed, purpose=devblog) 최신 배치
        val filters = buildList {
            tag?.let { add(BatchFilter.JsonArrayContains("tags", """["$it"]""")) }
            since?.let { add(BatchFilter.TimestamptzGte("published_at", it)) }
        }
        val items = batchService.findLatest(
            "feed",
            "devblog",
            filters,
            BatchSortKey.IntDesc("reactions_count"),
            limit,
        ).map { DevblogItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size, returned = items.size))
    }

    @Tag(name = "Dev.to Crawling")
    @Operation(summary = "Dev.to 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/devto-hashnode")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "Dev.to")
}
