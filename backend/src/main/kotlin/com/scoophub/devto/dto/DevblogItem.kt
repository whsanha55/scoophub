package com.scoophub.devto.dto

import com.scoophub.global.crawl.vo.BatchRow
import com.scoophub.global.jackson.scalar
import tools.jackson.databind.JsonNode

/** legacy `_devblog_item` — crawl_data row → devblog 응답 필드로 재구성 */
data class DevblogItem(
    val id: Long,
    val articleId: Long?,
    val title: String?,
    val url: String?,
    val author: String?,
    val description: String?,
    val reactionsCount: Int?,
    val commentsCount: Int?,
    val readingTime: Int?,
    val tags: List<String>?,
    val source: String?,
    val publishedAt: String?,
    val fetchedAt: String?,
) {
    companion object {
        fun from(row: BatchRow) = with(row.response) {
            DevblogItem(
                id = row.id,
                articleId = this["article_id"]?.takeIf { !it.isNull }?.asLong(),
                title = scalar("title"),
                url = scalar("url"),
                author = scalar("author"),
                description = scalar("description"),
                reactionsCount = this["reactions_count"]?.takeIf { !it.isNull }?.asInt(),
                commentsCount = this["comments_count"]?.takeIf { !it.isNull }?.asInt(),
                readingTime = this["reading_time"]?.takeIf { !it.isNull }?.asInt(),
                tags = this["tags"]?.takeIf { it.isArray }?.toList()?.map { it.asText() },
                source = scalar("source"),
                publishedAt = scalar("published_at"),
                fetchedAt = scalar("fetched_at"),
            )
        }
    }
}
