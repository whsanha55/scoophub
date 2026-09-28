package com.scoophub.global.schedule.repository

import com.scoophub.global.schedule.entity.CrawlScheduleEntity
import com.scoophub.global.schedule.entity.CrawlScheduleId
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlScheduleRepository : JpaRepository<CrawlScheduleEntity, CrawlScheduleId> {
    fun findByCrawlerAndJobId(crawler: String, jobId: String): CrawlScheduleEntity?
}
