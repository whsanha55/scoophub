package com.scoophub.global.crawl.entity

import com.scoophub.global.crawl.enums.CrawlStatusEnum
import com.scoophub.global.crawl.enums.CrawlStatusEnumConverter
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** V1__system.sql crawl_logs */
@Entity
@Table(name = "crawl_logs")
class CrawlLogEntity(
    val crawler: String,
    val crawlerDetail: String,
    @Convert(converter = CrawlStatusEnumConverter::class)
    val status: CrawlStatusEnum,
    val itemsFetched: Int,
    val itemsNew: Int,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null
        protected set
}
