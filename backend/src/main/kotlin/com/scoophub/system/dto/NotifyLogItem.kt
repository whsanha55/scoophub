package com.scoophub.system.dto

import java.sql.ResultSet
import java.time.Instant

/** legacy notify_log + routes 조인 row */
data class NotifyLogItem(
    val id: Long,
    val routeId: Long,
    val payloadKey: String,
    val status: String,
    val error: String?,
    val sentAt: Instant,
    val category: String?,
    val purpose: String?,
) {
    companion object {
        fun of(rs: ResultSet): NotifyLogItem = NotifyLogItem(
            id = rs.getLong("id"),
            routeId = rs.getLong("route_id"),
            payloadKey = rs.getString("payload_key"),
            status = rs.getString("status"),
            error = rs.getString("error"),
            sentAt = rs.getTimestamp("sent_at").toInstant(),
            category = rs.getString("category"),
            purpose = rs.getString("purpose"),
        )
    }
}
