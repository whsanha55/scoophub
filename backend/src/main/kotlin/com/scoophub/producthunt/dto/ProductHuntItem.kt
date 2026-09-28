package com.scoophub.producthunt.dto

import com.scoophub.global.crawl.LatestBatchQuery
import com.scoophub.global.jackson.scalar

/** legacy `_ph_item` — crawl_data row → product hunt 응답 필드로 재구성 */
data class ProductHuntItem(
    val id: Long,
    val phId: String?,
    val name: String?,
    val tagline: String?,
    val slug: String?,
    val phUrl: String?,
    val websiteUrl: String?,
    val votesCount: Int?,
    val commentsCount: Int?,
    val topics: List<String>?,
    val featuredAt: String?,
    val postedAt: String?,
    val fetchedAt: String?,
) {
    companion object {
        fun from(row: LatestBatchQuery.BatchRow) = with(row.response) {
            ProductHuntItem(
                id = row.id,
                phId = scalar("ph_id"),
                name = scalar("name"),
                tagline = scalar("tagline"),
                slug = scalar("slug"),
                phUrl = scalar("ph_url"),
                websiteUrl = scalar("website_url"),
                votesCount = this["votes_count"]?.takeIf { !it.isNull }?.asInt(),
                commentsCount = this["comments_count"]?.takeIf { !it.isNull }?.asInt(),
                topics = this["topics"]?.takeIf { it.isArray }?.toList()?.map { it.asText() },
                featuredAt = scalar("featured_at"),
                postedAt = scalar("posted_at"),
                fetchedAt = scalar("fetched_at"),
            )
        }
    }
}
