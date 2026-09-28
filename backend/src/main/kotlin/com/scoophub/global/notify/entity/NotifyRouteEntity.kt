package com.scoophub.global.notify.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** V13__notify.sql — (category, purpose) → 채널·토픽 매핑. ''(빈문자열) = wildcard */
@Entity
@Table(name = "notify_routes")
class NotifyRouteEntity(
    /** GENERATED ALWAYS AS IDENTITY — 앱에서 대입하지 않음 */
    @Id
    val id: Long,
    val category: String,
    val purpose: String,
    val channel: String,
    val chatId: String,
    /** forum topic thread_id. null=미생성(발신 시점 자동 생성 대상) */
    var topicId: Long?,
    /** 토픽 자동생성용 이름. ''=수동(자동생성 안 함) */
    val topicName: String,
    val enabled: Boolean,
    @Column(insertable = false, updatable = false)
    val createdAt: Instant,
    @Column(insertable = false, updatable = false)
    val updatedAt: Instant,
)
