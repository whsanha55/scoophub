package com.scoophub.system.dto

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
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
        fun of(rs: ResultSet, jsonMapper: JsonMapper): NewsSourceItem = NewsSourceItem(
            id = rs.getInt("id"),
            crawler = rs.getString("crawler"),
            name = rs.getString("name"),
            url = rs.getString("url"),
            active = rs.getBoolean("active"),
            config = jsonMapper.readTree(rs.getString("config")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
