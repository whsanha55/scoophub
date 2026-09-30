package com.scoophub.global.crawl.repository

import com.scoophub.global.crawl.entity.CrawlLogEntity
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

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

    /** news cutoff — 직전 성공 크롤 완료 시각 */
    @Query(
        """
        SELECT max(finished_at) FROM crawl_logs
        WHERE crawler = :crawler AND crawler_detail = :detail AND status IN ('success', 'partial')
        """,
        nativeQuery = true,
    )
    fun findLastFinishedAt(crawler: String, detail: String): Instant?
}
