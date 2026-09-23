package com.scoophub.core.crawl

import com.scoophub.core.api.ApiResponse
import com.scoophub.core.api.ErrorDetail
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant

/** legacy `BaseCrawler.run()` + `BaseRouter` 수동 트리거 */
@Component
class CrawlRunner(
    private val crawlLogRepository: CrawlLogRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** fetch → crawl_logs 기록 → 완료 이벤트. 예외는 삼키고 error 로그 후 null */
    fun run(crawler: Crawler): CrawlResult? {
        log.info("crawl start - crawler={} detail={}", crawler.name, crawler.detail)
        val startedAt = Instant.now()
        return try {
            val result = crawler.fetch()
            val status = if (result.errors.isEmpty()) "success" else "partial"
            saveLog(crawler, status, result, startedAt)
            if (crawler.name != NEWS) {
                eventPublisher.publishEvent(CrawlCompletedEvent(crawler.name, crawler.detail, result))
            }
            log.info(
                "crawl done - crawler={} detail={}, status={}, fetched={}, new={}",
                crawler.name, crawler.detail, status, result.itemsFetched, result.itemsNew,
            )
            result
        } catch (e: Exception) {
            log.error("[{}] Crawl failed: {}", crawler.name, e.message, e)
            saveLog(crawler, "error", CrawlResult(errors = listOf(e.message ?: e.toString())), startedAt)
            null
        }
    }

    /**
     * `POST /api/crawling/{domain}` 응답. 실패해도 HTTP 200 + success=false (legacy 동일).
     * @param label 실패 메시지용 표시명 (legacy api_tag, 예: "Hacker News")
     */
    fun trigger(crawler: Crawler, label: String): ApiResponse<CrawlTriggerData> {
        log.info("manual {} crawl triggered", label)
        val result = run(crawler)
            ?: return ApiResponse(success = false, error = ErrorDetail(code = "crawl_failed", message = "$label 크롤 실패"))
        return ApiResponse.ok(
            CrawlTriggerData(
                crawler = crawler.name,
                crawlerDetail = crawler.detail,
                itemsFetched = result.itemsFetched,
                itemsNew = result.itemsNew,
                errors = result.errors.ifEmpty { null },
            ),
        )
    }

    private fun saveLog(crawler: Crawler, status: String, result: CrawlResult, startedAt: Instant) {
        crawlLogRepository.save(
            CrawlLog(
                crawler = crawler.name,
                crawlerDetail = crawler.detail,
                status = status,
                itemsFetched = result.itemsFetched,
                itemsNew = result.itemsNew,
                errorMessage = result.errors.ifEmpty { null }?.joinToString("; "),
                startedAt = startedAt,
                finishedAt = Instant.now(),
            ),
        )
    }

    companion object {
        private const val NEWS = "news"
    }
}

data class CrawlTriggerData(
    val crawler: String,
    val crawlerDetail: String,
    val itemsFetched: Int,
    val itemsNew: Int,
    val errors: List<String>?,
)
