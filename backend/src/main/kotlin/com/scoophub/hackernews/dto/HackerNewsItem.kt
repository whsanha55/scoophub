package com.scoophub.hackernews.dto

import com.scoophub.global.crawl.vo.BatchRow
import com.scoophub.global.jackson.scalar
import tools.jackson.databind.JsonNode

/** legacy `_hn_item` — crawl_data row → hacker news 응답 필드로 재구성 */
data class HackerNewsItem(
    val id: Long,
    val hnId: Long?,
    val title: String?,
    val url: String?,
    val byUser: String?,
    val score: Int?,
    val descendants: Int?,
    val itemType: String?,
    val bodyText: String?,
    val postedAt: String?,
    val fetchedAt: String?,
) {
    companion object {
        fun from(row: BatchRow) = with(row.response) {
            HackerNewsItem(
                id = row.id,
                hnId = this["hn_id"]?.takeIf { !it.isNull }?.asLong(),
                title = scalar("title"),
                url = scalar("url"),
                byUser = scalar("by_user"),
                score = this["score"]?.takeIf { !it.isNull }?.asInt(),
                descendants = this["descendants"]?.takeIf { !it.isNull }?.asInt(),
                itemType = scalar("item_type"),
                bodyText = scalar("body_text"),
                postedAt = scalar("posted_at"),
                fetchedAt = scalar("fetched_at"),
            )
        }

        private fun JsonNode.scalar(field: String): String? =
            this[field]?.takeIf { !it.isNull && !it.isMissingNode }?.asText()
    }
}
