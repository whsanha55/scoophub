package com.scoophub.youtube

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.global.crawl.service.LatestBatchService
import com.scoophub.global.crawl.vo.BatchFilter
import com.scoophub.global.crawl.vo.BatchSortKey
import com.scoophub.youtube.dto.YoutubeTrendingItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

private val log = KotlinLogging.logger {}

@RestController
class YoutubeTrendingController(
    private val batchService: LatestBatchService,
    private val crawlRunner: CrawlRunner,
    private val crawler: YoutubeTrendingCrawler,
    private val clock: Clock,
) {
    @Tag(name = "YouTube Trending")
    @Operation(summary = "YouTube 트렌딩 비디오 조회")
    @GetMapping("/api/youtube-trending")
    fun getYoutubeTrending(
        @RequestParam(defaultValue = "25") limit: Int = 25,
        @RequestParam(name = "region_code", defaultValue = "KR") regionCode: String = "KR",
        @RequestParam(name = "category_id") categoryId: String? = null,
    ): ApiResponse<List<YoutubeTrendingItem>> {
        log.info { "get_youtube_trending requested: region=$regionCode category=$categoryId limit=$limit" }
        // crawl_data(category=feed, purpose=youtube). region별 최신 배치
        val filters = buildList {
            add(BatchFilter.TextEq("region_code", regionCode))
            categoryId?.let { add(BatchFilter.TextEq("category_id", it)) }
        }
        val items = batchService.findLatest(
            "feed",
            "youtube",
            filters,
            BatchSortKey.LongDesc("view_count"),
            limit,
            baseFilters = listOf(BatchFilter.TextEq("region_code", regionCode)),
        ).map { YoutubeTrendingItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size, returned = items.size))
    }

    @Tag(name = "YouTube Trending Crawling")
    @Operation(summary = "YouTube Trending 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/youtube-trending")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "YouTube Trending")
}
