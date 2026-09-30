package com.scoophub.github

import com.scoophub.github.dto.GithubTrendingItem
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

private val log = KotlinLogging.logger {}

@RestController
class GithubTrendingController(
    private val batchService: LatestBatchService,
    private val crawlRunner: CrawlRunner,
    private val crawler: GithubTrendingCrawler,
    private val clock: Clock,
) {
    @Tag(name = "GitHub Trending")
    @Operation(summary = "GitHub 트렌딩 리포지토리 조회")
    @GetMapping("/api/github-trending")
    fun getGithubTrending(
        @RequestParam(defaultValue = "daily") period: String = "daily",
        language: String? = null,
        @RequestParam(defaultValue = "25") limit: Int = 25,
    ): ApiResponse<List<GithubTrendingItem>> {
        log.info { "get_github_trending requested: period=$period language=$language limit=$limit" }
        // crawl_data(category=community, purpose=github). period별 최신 배치
        val filters = buildList {
            add(BatchFilter.TextEq("period", period))
            language?.let { add(BatchFilter.TextEq("language", it)) }
        }
        val items = batchService.findLatest(
            "community",
            "github",
            filters,
            BatchSortKey.IntDesc("current_period_stars"),
            limit,
            baseFilters = listOf(BatchFilter.TextEq("period", period)),
        ).map { GithubTrendingItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size, returned = items.size))
    }

    @Tag(name = "GitHub Trending Crawling")
    @Operation(summary = "GitHub Trending 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/github-trending")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "GitHub Trending")
}
