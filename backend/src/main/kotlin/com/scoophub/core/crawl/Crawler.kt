package com.scoophub.core.crawl

/** legacy `CrawlResult` */
data class CrawlResult(
    val itemsFetched: Int = 0,
    val itemsNew: Int = 0,
    val errors: List<String> = emptyList(),
    val newArticleIds: List<Long> = emptyList(),
)

/**
 * legacy `BaseCrawler` 의 fetch 부분. 도메인은 이것만 구현하고,
 * crawl_logs 기록·완료 이벤트는 [CrawlRunner] 가 담당한다.
 */
interface Crawler {
    /** crawl_logs.crawler / notify 라우팅 키 */
    val name: String

    /** crawl_logs.crawler_detail (같은 crawler 의 세부 구분, 없으면 빈 문자열) */
    val detail: String get() = ""

    fun fetch(): CrawlResult
}

/** 크롤 성공(success/partial) 후 발행. news 는 요약 후 자체 발신하므로 제외. notify 가 구독한다. */
data class CrawlCompletedEvent(val crawler: String, val detail: String, val result: CrawlResult)
