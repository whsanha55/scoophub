package com.scoophub.github.dto

import com.scoophub.global.crawl.LatestBatchQuery
import com.scoophub.global.jackson.scalar

/** legacy `_github_item` — crawl_data row → github trending 응답 필드로 재구성 */
data class GithubTrendingItem(
    val id: Long,
    val fullname: String?,
    val author: String?,
    val name: String?,
    val url: String?,
    val description: String?,
    val language: String?,
    val stars: Int?,
    val forks: Int?,
    val currentPeriodStars: Int?,
    val period: String?,
    val fetchedAt: String?,
) {
    companion object {
        fun from(row: LatestBatchQuery.BatchRow) = with(row.response) {
            GithubTrendingItem(
                id = row.id,
                fullname = scalar("fullname"),
                author = scalar("author"),
                name = scalar("name"),
                url = scalar("url"),
                description = scalar("description"),
                language = scalar("language"),
                stars = this["stars"]?.takeIf { !it.isNull }?.asInt(),
                forks = this["forks"]?.takeIf { !it.isNull }?.asInt(),
                currentPeriodStars = this["current_period_stars"]?.takeIf { !it.isNull }?.asInt(),
                period = scalar("period"),
                fetchedAt = scalar("fetched_at"),
            )
        }
    }
}
