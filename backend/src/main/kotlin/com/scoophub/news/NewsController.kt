package com.scoophub.news

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.news.dto.NewsArticleItem
import com.scoophub.news.service.NewsArticleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant

@RestController
class NewsController(private val newsService: NewsArticleService, private val clock: Clock) {
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
        @RequestParam(defaultValue = "1") page: Int = 1,
        @RequestParam symbol: String? = null,
    ): ApiResponse<List<NewsArticleItem>> {
        val page = newsService.findArticles(minutes, from, to, category, minImportance, symbol, limit, page)
        val articles = page.articles.map { NewsArticleItem.from(it) }
        return ApiResponse.ok(
            articles,
            ResponseMeta(clock.instant(), total = page.total, returned = articles.size),
        )
    }

    @Tag(name = "News")
    @Operation(summary = "뉴스 기사 단건 조회")
    @GetMapping("/api/news/{article_id}")
    fun getNewsById(@PathVariable("article_id") articleId: Long): ApiResponse<NewsArticleItem> {
        val row = newsService.findById(articleId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Article $articleId not found",
            )
        return ApiResponse.ok(NewsArticleItem.from(row), ResponseMeta(clock.instant()))
    }
}
