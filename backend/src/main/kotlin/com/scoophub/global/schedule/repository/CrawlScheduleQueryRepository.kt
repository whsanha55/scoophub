package com.scoophub.global.schedule.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CrawlScheduleQueryRepository(private val jdbcClient: JdbcClient) {

    /** null 필드는 유지. 변경할 필드가 없으면 false */
    fun update(
        crawler: String,
        jobId: String,
        schedules: List<String>?,
        scheduleMinutes: Int?,
        enabled: Boolean?,
    ): Boolean {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        schedules?.let {
            sets += "schedules = CAST(:schedules AS text[])"
            params["schedules"] = it.toTypedArray()
        }
        scheduleMinutes?.let {
            sets += "schedule_minutes = :scheduleMinutes"
            params["scheduleMinutes"] = it
        }
        enabled?.let {
            sets += "enabled = :enabled"
            params["enabled"] = it
        }
        if (sets.isEmpty()) {
            return false
        }
        jdbcClient.sql(
            "UPDATE crawl_schedule SET ${sets.joinToString(", ")}, updated_at = now() " +
                "WHERE crawler = :crawler AND job_id = :jobId",
        )
            .param("crawler", crawler)
            .param("jobId", jobId)
            .params(params)
            .update()
        return true
    }
}
