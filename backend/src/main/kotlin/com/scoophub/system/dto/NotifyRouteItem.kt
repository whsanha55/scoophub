package com.scoophub.system.dto

import java.sql.ResultSet
import java.time.Instant

/** legacy notify_routes row */
data class NotifyRouteItem(
    val id: Long,
    val category: String,
    val purpose: String,
    val channel: String,
    val chatId: String,
    val topicId: Long?,
    val topicName: String,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(rs: ResultSet): NotifyRouteItem = NotifyRouteItem(
            id = rs.getLong("id"),
            category = rs.getString("category"),
            purpose = rs.getString("purpose"),
            channel = rs.getString("channel"),
            chatId = rs.getString("chat_id"),
            topicId = rs.getObject("topic_id") as Long?,
            topicName = rs.getString("topic_name"),
            enabled = rs.getBoolean("enabled"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
