package com.scoophub.global.schedule.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant

/** V11__crawl_schedule.sql — 크롤 주기 DB 동적 관리 */
@Entity
@Table(name = "crawl_schedule")
@IdClass(CrawlScheduleId::class)
class CrawlScheduleEntity(
    @Id
    val crawler: String,
    @Id
    val jobId: String,
    val scheduleType: String,
    @JdbcTypeCode(SqlTypes.ARRAY)
    val schedules: List<String>,
    val scheduleMinutes: Int?,
    val enabled: Boolean,
    val description: String,
    @Column(insertable = false, updatable = false)
    val updatedAt: Instant,
)

data class CrawlScheduleId(val crawler: String = "", val jobId: String = "") : Serializable
