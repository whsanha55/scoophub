package com.scoophub.system.dto

import com.scoophub.global.notify.vo.NotifyLogRow
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
        fun from(row: NotifyLogRow) = NotifyLogItem(
            id = row.id,
            routeId = row.routeId,
            payloadKey = row.payloadKey,
            status = row.status,
            error = row.error,
            sentAt = row.sentAt,
            category = row.category,
            purpose = row.purpose,
        )
    }
}
