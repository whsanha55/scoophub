package com.scoophub.news.dto

import com.scoophub.news.vo.NewsSourceRow
import tools.jackson.databind.JsonNode
import java.time.Instant

/** legacy crawl_sources row */
data class NewsSourceItem(
    val id: Int,
    val crawler: String,
    val name: String,
    val url: String,
    val active: Boolean,
    val config: JsonNode,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(row: NewsSourceRow) = NewsSourceItem(
            id = row.id,
            crawler = row.crawler,
            name = row.name,
            url = row.url,
            active = row.active,
            config = row.config,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }
}
