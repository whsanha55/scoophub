package com.scoophub.global.crawl.repository

import com.scoophub.global.crawl.entity.CrawlLogEntity
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlLogRepository : JpaRepository<CrawlLogEntity, Int>
