package com.scoophub.news.vo

import java.time.Instant

/** feed_news 조회 프로젝션 */
data class NewsArticleRow(
    val id: Int,
    val source: String,
    val category: String?,
    val title: String,
    val summary: String?,
    val url: String,
    val normalizedUrl: String?,
    val publishedAt: Instant?,
    val importance: Int,
    val summaryStatus: String,
    val duplicated: Boolean,
    val duplicatedNewsId: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NewsArticlePage(val total: Int, val articles: List<NewsArticleRow>)
