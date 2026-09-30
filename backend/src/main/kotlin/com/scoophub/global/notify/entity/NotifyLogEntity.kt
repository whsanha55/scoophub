package com.scoophub.global.notify.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** V13__notify.sql — 발신 이력 + 중복 발신 방지 (route_id, payload_key 유니크) */
@Entity
@Table(name = "notify_log")
class NotifyLogEntity(
    /** GENERATED ALWAYS AS IDENTITY — 앱에서 대입하지 않음 */
    @Id
    val id: Long,
    val routeId: Long,
    /** 발신 단위 논리키 (예: 'news:alpaca:42') */
    val payloadKey: String,
    val status: String,
    val error: String?,
    @Column(insertable = false, updatable = false)
    val sentAt: Instant,
)
