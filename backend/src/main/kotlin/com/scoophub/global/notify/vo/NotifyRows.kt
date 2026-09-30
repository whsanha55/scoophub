package com.scoophub.global.notify.vo

import java.time.Instant

/** notify_routes 조회 프로젝션 */
data class NotifyRouteRow(
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
)

/** notify_log + notify_routes 조인 프로젝션 */
data class NotifyLogRow(
    val id: Long,
    val routeId: Long,
    val payloadKey: String,
    val status: String,
    val error: String?,
    val sentAt: Instant,
    val category: String?,
    val purpose: String?,
)

/** notify_routes 부분 수정 값. null 필드는 유지 */
data class NotifyRouteUpdate(
    val category: String? = null,
    val purpose: String? = null,
    val channel: String? = null,
    val chatId: String? = null,
    val topicId: Long? = null,
    val topicName: String? = null,
    val enabled: Boolean? = null,
)
