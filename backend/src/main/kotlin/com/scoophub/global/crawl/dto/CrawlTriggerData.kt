package com.scoophub.global.crawl.dto

/** `POST /api/crawling/{domain}` 응답 data */
data class CrawlTriggerData(
    val crawler: String,
    val crawlerDetail: String,
    val itemsFetched: Int,
    val itemsNew: Int,
    val errors: List<String>?,
)
