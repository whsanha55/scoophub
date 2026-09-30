package com.scoophub.system.dto

import com.scoophub.global.notify.vo.NotifyRouteRow
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
        fun from(row: NotifyRouteRow) = NotifyRouteItem(
            id = row.id,
            category = row.category,
            purpose = row.purpose,
            channel = row.channel,
            chatId = row.chatId,
            topicId = row.topicId,
            topicName = row.topicName,
            enabled = row.enabled,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }
}
