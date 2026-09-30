package com.scoophub.news

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ErrorDetail
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.news.dto.NewsArticleItem
import com.scoophub.news.service.NewsService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

@RestController
class NewsController(
    private val newsService: NewsService,
    private val crawlRunner: CrawlRunner,
    private val crawler: NewsCrawler,
    private val summarizer: NewsSummarizer,
    private val clock: Clock,
) {
    @Tag(name = "News")
    @Operation(summary = "뉴스 기사 목록 조회")
    @GetMapping("/api/news")
    fun getNews(
        minutes: Int? = null,
        @RequestParam("from") from: Instant? = null,
        @RequestParam to: Instant? = null,
        category: String? = null,
        @RequestParam("min_importance") minImportance: Int? = null,
        @RequestParam(defaultValue = "20") limit: Int = 20,
    ): ApiResponse<List<NewsArticleItem>> {
        val page = newsService.findArticles(minutes, from, to, category, minImportance, limit)
        val articles = page.articles.map { NewsArticleItem.from(it) }
        return ApiResponse.ok(
            articles,
            ResponseMeta(clock.instant(), total = page.total, returned = articles.size),
        )
    }

    @Tag(name = "News")
    @Operation(summary = "뉴스 기사 단건 조회")
    @GetMapping("/api/news/{article_id}")
    fun getNewsById(@PathVariable("article_id") articleId: Int): ApiResponse<NewsArticleItem> {
        val row = newsService.findById(articleId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Article $articleId not found",
            )
        return ApiResponse.ok(NewsArticleItem.from(row), ResponseMeta(clock.instant()))
    }

    data class NewsTriggerData(
        val crawler: String,
        val itemsFetched: Int,
        val itemsNew: Int,
        val errors: List<String>?,
        val summary: NewsSummarizer.Result?,
    )

    @Tag(name = "News Crawling")
    @Operation(summary = "뉴스 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/news")
    fun triggerCrawl(): ApiResponse<NewsTriggerData> {
        log.info { "crawling_news 시작 - 뉴스 크롤 수동 실행" }
        val result = crawlRunner.run(crawler)
            ?: return ApiResponse(
                success = false,
                error = ErrorDetail(code = "crawl_failed", message = "뉴스 크롤 실패"),
                meta = ResponseMeta(clock.instant()),
            )

        val summary = try {
            summarizer.summarizeIncomplete()
        } catch (e: Exception) {
            log.error { "요약 실패: ${e.message}" }
            null
        }
        return ApiResponse.ok(
            NewsTriggerData(
                crawler = "news",
                itemsFetched = result.itemsFetched,
                itemsNew = result.itemsNew,
                errors = result.errors.ifEmpty { null },
                summary = summary,
            ),
            ResponseMeta(clock.instant()),
        )
    }

    @Tag(name = "News Crawling")
    @Operation(summary = "뉴스 요약 재시도")
    @SuperOnly
    @PostMapping("/api/crawling/news/summarize/retry")
    fun summarizeRetry(): ApiResponse<NewsSummarizer.Result> {
        log.info { "summarize_news_retry 시작 - 미완료 기사 요약 재시도" }
        return try {
            ApiResponse.ok(summarizer.summarizeIncomplete(), ResponseMeta(clock.instant()))
        } catch (e: Exception) {
            ApiResponse(
                success = false,
                error = ErrorDetail(code = "summarize_failed", message = "요약 실패: $e"),
                meta = ResponseMeta(clock.instant()),
            )
        }
    }
}
