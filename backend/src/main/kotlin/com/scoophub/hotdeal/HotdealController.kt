package com.scoophub.hotdeal

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HotdealController(private val crawlRunner: CrawlRunner, private val zodCrawler: ZodCrawler) {

    @Tag(name = "Hotdeal Crawling")
    @Operation(summary = "알구더 핫딜 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/hotdeal-zod")
    fun triggerZodCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(zodCrawler, "알구더")
}
