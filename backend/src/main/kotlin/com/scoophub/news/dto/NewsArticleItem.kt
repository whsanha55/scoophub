package com.scoophub.news.dto

import com.scoophub.news.vo.NewsArticleRow
import java.time.Instant

/** legacy feed_news row → dict (snake_case 직렬화) */
data class NewsArticleItem(
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
) {
    companion object {
        fun from(row: NewsArticleRow) = NewsArticleItem(
            id = row.id,
            source = row.source,
            category = row.category,
            title = row.title,
            summary = row.summary,
            url = row.url,
            normalizedUrl = row.normalizedUrl,
            publishedAt = row.publishedAt,
            importance = row.importance,
            summaryStatus = row.summaryStatus,
            duplicated = row.duplicated,
            duplicatedNewsId = row.duplicatedNewsId,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }
}
