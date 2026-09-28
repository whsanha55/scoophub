package com.scoophub.global.schedule.repository

import com.scoophub.global.schedule.entity.CrawlConfigEntity
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlConfigRepository : JpaRepository<CrawlConfigEntity, String> {
    fun findByCrawler(crawler: String): CrawlConfigEntity?
}
