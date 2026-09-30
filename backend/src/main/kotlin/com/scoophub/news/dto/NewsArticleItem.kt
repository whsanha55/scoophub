package com.scoophub.news.dto

import com.scoophub.news.vo.AlpacaArticleRow
import java.time.Instant

data class NewsArticleItem(
    val id: Long,
    val source: String,
    val headline: String,
    val summary: String?,
    val author: String?,
    val url: String?,
    val symbols: List<String>,
    val publishedAt: Instant,
    val sourceUpdatedAt: Instant,
    val importance: Int?,
    val category: String?,
    val summaryKo: String?,
    val status: String,
    val decisionReason: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        fun from(row: AlpacaArticleRow): NewsArticleItem = NewsArticleItem(
            id = row.id, source = row.source, headline = row.headline, summary = row.summary, author = row.author,
            url = row.url, symbols = row.symbols, publishedAt = row.publishedAt, sourceUpdatedAt = row.sourceUpdatedAt,
            importance = row.importance, category = row.category, summaryKo = row.summaryKo, status = row.status,
            decisionReason = row.decisionReason, createdAt = row.createdAt, updatedAt = row.updatedAt,
        )
    }
}
