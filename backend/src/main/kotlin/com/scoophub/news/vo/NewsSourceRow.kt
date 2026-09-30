package com.scoophub.news.vo

import tools.jackson.databind.JsonNode
import java.time.Instant

/** crawl_sources(news) 조회 프로젝션 */
data class NewsSourceRow(
    val id: Int,
    val crawler: String,
    val name: String,
    val url: String,
    val active: Boolean,
    val config: JsonNode,
    val createdAt: Instant,
    val updatedAt: Instant,
)
