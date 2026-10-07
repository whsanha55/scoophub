package com.scoophub.system

import com.scoophub.global.schedule.ScheduledJob
import com.scoophub.system.service.DataRetentionService
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/** 보존 기간 경과 데이터 정리 — job_id data_retention (V30 seed, 매일 04:00) */
@Component
class DataRetentionJob(private val service: DataRetentionService) : ScheduledJob {
    override val crawler = "system"
    override val jobId = "data_retention"

    override fun run(params: JsonNode) = service.purge()
}
