package com.scoophub.system.dto

import com.scoophub.global.schedule.entity.CrawlScheduleEntity
import java.time.Instant

/** legacy `_attach_runtime` 조인 전 crawl_schedule row */
data class ScheduleItem(
    val crawler: String,
    val jobId: String,
    val scheduleType: String,
    val schedules: List<String>,
    val scheduleMinutes: Int?,
    val enabled: Boolean,
    val description: String,
    val updatedAt: Instant,
    /** 런타임 상태 — KST isoformat. 미등록 잡은 null */
    val nextRunTime: String? = null,
    /** 등록됐으면 paused 여부, 미등록이면 null */
    val paused: Boolean? = null,
) {
    companion object {
        fun from(entity: CrawlScheduleEntity) = ScheduleItem(
            crawler = entity.crawler,
            jobId = entity.jobId,
            scheduleType = entity.scheduleType,
            schedules = entity.schedules,
            scheduleMinutes = entity.scheduleMinutes,
            enabled = entity.enabled,
            description = entity.description,
            updatedAt = entity.updatedAt,
        )
    }
}
