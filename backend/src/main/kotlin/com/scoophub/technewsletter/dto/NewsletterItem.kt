package com.scoophub.technewsletter.dto

import com.scoophub.global.crawl.LatestBatchQuery
import com.scoophub.global.jackson.scalar

/** legacy `_newsletter_item` — crawl_data row → newsletter 응답 필드로 재구성 (url = row.key) */
data class NewsletterItem(
    val id: Long,
    val url: String,
    val title: String?,
    val source: String?,
    val summary: String?,
    val author: String?,
    val category: String?,
    val publishedAt: String?,
    val fetchedAt: String?,
) {
    companion object {
        fun from(row: LatestBatchQuery.BatchRow) = with(row.response) {
            NewsletterItem(
                id = row.id,
                url = row.key,
                title = scalar("title"),
                source = scalar("source"),
                summary = scalar("summary"),
                author = scalar("author"),
                category = scalar("category"),
                publishedAt = scalar("published_at"),
                fetchedAt = scalar("fetched_at"),
            )
        }
    }
}
