package com.scoophub.system.dto

import com.scoophub.global.crawl.entity.CrawlLogEntity
import java.time.Instant

/** legacy crawl_logs row → dict (status 는 legacy 소문자 표기 유지) */
data class CrawlLogItem(
    val id: Int?,
    val crawler: String,
    val crawlerDetail: String,
    val status: String,
    val itemsFetched: Int,
    val itemsNew: Int,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
) {
    companion object {
        fun from(entity: CrawlLogEntity) = CrawlLogItem(
            id = entity.id,
            crawler = entity.crawler,
            crawlerDetail = entity.crawlerDetail,
            status = entity.status.name.lowercase(),
            itemsFetched = entity.itemsFetched,
            itemsNew = entity.itemsNew,
            errorMessage = entity.errorMessage,
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt,
        )
    }
}
