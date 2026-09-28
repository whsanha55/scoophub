package com.scoophub.global.crawl.repository

import com.scoophub.global.crawl.entity.CrawlLogEntity
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlLogRepository : JpaRepository<CrawlLogEntity, Int> {

    /** system 크롤 로그 조회 — 필터 조합 */
    fun findByCrawlerAndCrawlerDetailOrderByStartedAtDesc(
        crawler: String,
        crawlerDetail: String,
        limit: Limit,
    ): List<CrawlLogEntity>

    fun findByCrawlerOrderByStartedAtDesc(crawler: String, limit: Limit): List<CrawlLogEntity>

    fun findByCrawlerDetailOrderByStartedAtDesc(crawlerDetail: String, limit: Limit): List<CrawlLogEntity>

    fun findAllByOrderByStartedAtDesc(limit: Limit): List<CrawlLogEntity>
}
