package com.scoophub.core.crawl

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** V1__system.sql crawl_logs */
@Entity
@Table(name = "crawl_logs")
class CrawlLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,
    val crawler: String,
    val crawlerDetail: String,
    /** success / partial / error */
    val status: String,
    val itemsFetched: Int,
    val itemsNew: Int,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

interface CrawlLogRepository : JpaRepository<CrawlLog, Int>
