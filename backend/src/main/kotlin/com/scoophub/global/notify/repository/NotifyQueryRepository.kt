package com.scoophub.global.notify.repository

import com.scoophub.global.notify.vo.NotifyLogRow
import com.scoophub.global.notify.vo.NotifyRouteRow
import com.scoophub.global.notify.vo.NotifyRouteUpdate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** notify_routes / notify_log 관리 API 쿼리 — legacy `system/notify_router.py` */
@Repository
class NotifyQueryRepository(private val jdbcClient: JdbcClient) {

    private val routeMapper = RowMapper { rs, _ ->
        NotifyRouteRow(
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

    fun findAllRoutes(): List<NotifyRouteRow> = jdbcClient.sql(
        """
        SELECT id, category, purpose, channel, chat_id, topic_id, topic_name, enabled, created_at, updated_at
        FROM notify_routes ORDER BY id
        """.trimIndent(),
    ).query(routeMapper).list()

    fun findRoute(id: Long): NotifyRouteRow? = jdbcClient.sql(
        """
        SELECT id, category, purpose, channel, chat_id, topic_id, topic_name, enabled, created_at, updated_at
        FROM notify_routes WHERE id = :id
        """.trimIndent(),
    )
        .param("id", id)
        .query(routeMapper)
        .optional()
        .orElse(null)

    fun insertRoute(
        category: String,
        purpose: String,
        channel: String,
        chatId: String,
        topicId: Long?,
        topicName: String,
        enabled: Boolean,
    ): Long = jdbcClient.sql(
        """
        INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled)
        VALUES (:category, :purpose, :channel, :chatId, :topicId, :topicName, :enabled)
        RETURNING id
        """,
    )
        .param("category", category)
        .param("purpose", purpose)
        .param("channel", channel)
        .param("chatId", chatId)
        .param("topicId", topicId)
        .param("topicName", topicName)
        .param("enabled", enabled)
        .query { rs, _ -> rs.getLong(1) }
        .single()

    /** 변경할 필드가 없으면 false */
    fun updateRoute(id: Long, update: NotifyRouteUpdate): Boolean {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        listOf(
            "category" to update.category,
            "purpose" to update.purpose,
            "channel" to update.channel,
            "chat_id" to update.chatId,
            "topic_id" to update.topicId,
            "topic_name" to update.topicName,
            "enabled" to update.enabled,
        ).forEach { (column, value) ->
            if (value != null) {
                val name = "p${params.size}"
                sets += "$column = :$name"
                params[name] = value
            }
        }
        if (sets.isEmpty()) {
            return false
        }
        jdbcClient.sql(
            "UPDATE notify_routes SET ${sets.joinToString(", ")}, updated_at = now() WHERE id = :id",
        )
            .param("id", id)
            .params(params)
            .update()
        return true
    }

    fun deleteRoute(id: Long): Int = jdbcClient.sql("DELETE FROM notify_routes WHERE id = :id")
        .param("id", id)
        .update()

    /** (status, error) */
    fun findLogStatus(routeId: Long, payloadKey: String): Pair<String, String?>? = jdbcClient.sql(
        "SELECT status, error FROM notify_log WHERE route_id = :id AND payload_key = :key",
    )
        .param("id", routeId)
        .param("key", payloadKey)
        .query { rs, _ -> rs.getString("status") to rs.getString("error") }
        .optional()
        .orElse(null)

    fun findLogs(routeId: Long?, status: String?, limit: Int): List<NotifyLogRow> {
        var sql = """
            SELECT l.id, l.route_id, l.payload_key, l.status, l.error, l.sent_at, r.category, r.purpose
            FROM notify_log l LEFT JOIN notify_routes r ON r.id = l.route_id
        """
        val conditions = mutableListOf<String>()
        routeId?.let { conditions += "l.route_id = :routeId" }
        status?.let { conditions += "l.status = :status" }
        if (conditions.isNotEmpty()) {
            sql += " WHERE ${conditions.joinToString(" AND ")}"
        }
        sql += " ORDER BY l.sent_at DESC LIMIT :limit"

        var spec = jdbcClient.sql(sql).param("limit", limit)
        routeId?.let { spec = spec.param("routeId", it) }
        status?.let { spec = spec.param("status", it) }
        return spec.query { rs, _ ->
            NotifyLogRow(
                id = rs.getLong("id"),
                routeId = rs.getLong("route_id"),
                payloadKey = rs.getString("payload_key"),
                status = rs.getString("status"),
                error = rs.getString("error"),
                sentAt = rs.getTimestamp("sent_at").toInstant(),
                category = rs.getString("category"),
                purpose = rs.getString("purpose"),
            )
        }.list()
    }
}
