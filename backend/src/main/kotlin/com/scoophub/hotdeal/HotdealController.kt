package com.scoophub.hotdeal

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.hotdeal.dto.HotdealFeedItemResponse
import com.scoophub.hotdeal.service.HotdealFeedService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
class HotdealController(
    private val crawlRunner: CrawlRunner,
    private val zodCrawler: ZodCrawler,
    private val feedService: HotdealFeedService,
    private val clock: Clock,
) {

    @Tag(name = "Hotdeal")
    @Operation(summary = "핫딜 추천 피드 조회 (sort: recommended | latest)")
    @SuperOnly
    @GetMapping("/api/hotdeal/feed")
    fun getFeed(
        @RequestParam(defaultValue = "recommended") sort: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "1") page: Int,
    ): ApiResponse<List<HotdealFeedItemResponse>> {
        val feed = feedService.findFeed(sort, limit, page)
        return ApiResponse.ok(
            feed.items,
            ResponseMeta(clock.instant(), total = feed.total.toInt(), returned = feed.items.size),
        )
    }

    @Tag(name = "Hotdeal Crawling")
    @Operation(summary = "알구더 핫딜 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/hotdeal-zod")
    fun triggerZodCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(zodCrawler, "알구더")
}
