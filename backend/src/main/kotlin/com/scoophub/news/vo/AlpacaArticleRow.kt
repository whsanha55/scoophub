package com.scoophub.news.vo

import java.time.Instant

data class AlpacaArticleRow(
    val id: Long,
    val source: String,
    val headline: String,
    val summary: String?,
    val content: String?,
    val author: String?,
    val url: String?,
    val symbols: List<String>,
    val publishedAt: Instant,
    val sourceUpdatedAt: Instant,
    val status: String = "pending",
    val attempts: Int = 0,
    val importance: Int? = null,
    val category: String? = null,
    val summaryKo: String? = null,
    val decisionReason: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class ArticleAssessment(val importance: Int, val category: String, val summaryKo: String)
data class AlpacaArticlePage(val total: Int, val articles: List<AlpacaArticleRow>)
