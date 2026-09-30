package com.scoophub.youtube.dto

import com.scoophub.global.crawl.vo.BatchRow
import com.scoophub.global.jackson.scalar

/** legacy `_youtube_item` — crawl_data row → youtube 응답 필드로 재구성 */
data class YoutubeTrendingItem(
    val id: Long,
    val videoId: String?,
    val title: String?,
    val channelTitle: String?,
    val channelId: String?,
    val description: String?,
    val categoryId: String?,
    val publishedAt: String?,
    val viewCount: Long?,
    val likeCount: Long?,
    val commentCount: Long?,
    val duration: String?,
    val thumbnailUrl: String?,
    val regionCode: String?,
    val fetchedAt: String?,
) {
    companion object {
        fun from(row: BatchRow) = with(row.response) {
            YoutubeTrendingItem(
                id = row.id,
                videoId = scalar("video_id"),
                title = scalar("title"),
                channelTitle = scalar("channel_title"),
                channelId = scalar("channel_id"),
                description = scalar("description"),
                categoryId = scalar("category_id"),
                publishedAt = scalar("published_at"),
                viewCount = this["view_count"]?.takeIf { !it.isNull }?.asLong(),
                likeCount = this["like_count"]?.takeIf { !it.isNull }?.asLong(),
                commentCount = this["comment_count"]?.takeIf { !it.isNull }?.asLong(),
                duration = scalar("duration"),
                thumbnailUrl = scalar("thumbnail_url"),
                regionCode = scalar("region_code"),
                fetchedAt = scalar("fetched_at"),
            )
        }
    }
}
