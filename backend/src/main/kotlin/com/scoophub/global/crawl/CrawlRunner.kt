package com.scoophub.global.crawl

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ErrorDetail
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.global.crawl.entity.CrawlLogEntity
import com.scoophub.global.crawl.enums.CrawlStatusEnum
import com.scoophub.global.crawl.repository.CrawlLogRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

/** legacy `BaseCrawler.run()` + `BaseRouter` 수동 트리거 */
@Component
class CrawlRunner(
    private val crawlLogRepository: CrawlLogRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {

    /** fetch → crawl_logs 기록 → 완료 이벤트. 예외는 삼키고 error 로그 후 null */
    fun run(crawler: Crawler): CrawlResult? {
        log.info { "crawl start - crawler=${crawler.name} detail=${crawler.detail}" }
        val startedAt = clock.instant()
        return try {
            val result = crawler.fetch()
            val status = if (result.errors.isEmpty()) CrawlStatusEnum.SUCCESS else CrawlStatusEnum.PARTIAL
            saveLog(crawler, status, result, startedAt)
            if (crawler.name != NEWS) {
                eventPublisher.publishEvent(CrawlCompletedEvent(crawler.name, crawler.detail, result))
            }
            log.info {
                "crawl done - crawler=${crawler.name} detail=${crawler.detail}, " +
                    "status=$status, fetched=${result.itemsFetched}, new=${result.itemsNew}"
            }
            result
        } catch (e: Exception) {
            log.error(e) { "[${crawler.name}] Crawl failed: ${e.message}" }
            saveLog(crawler, CrawlStatusEnum.ERROR, CrawlResult(errors = listOf(e.message ?: e.toString())), startedAt)
            null
        }
    }

    /**
     * `POST /api/crawling/{domain}` 응답. 실패해도 HTTP 200 + success=false (legacy 동일).
     * @param label 실패 메시지용 표시명 (legacy api_tag, 예: "Hacker News")
     */
    fun trigger(crawler: Crawler, label: String): ApiResponse<CrawlTriggerData> {
        log.info { "manual $label crawl triggered" }
        val result = run(crawler)
            ?: return ApiResponse(
                success = false,
                error = ErrorDetail(code = "crawl_failed", message = "$label 크롤 실패"),
                meta = ResponseMeta(requestedAt = clock.instant()),
            )
        return ApiResponse.ok(
            CrawlTriggerData(
                crawler = crawler.name,
                crawlerDetail = crawler.detail,
                itemsFetched = result.itemsFetched,
                itemsNew = result.itemsNew,
                errors = result.errors.ifEmpty { null },
            ),
            ResponseMeta(requestedAt = clock.instant()),
        )
    }

    private fun saveLog(crawler: Crawler, status: CrawlStatusEnum, result: CrawlResult, startedAt: Instant) {
        crawlLogRepository.save(
            CrawlLogEntity(
                crawler = crawler.name,
                crawlerDetail = crawler.detail,
                status = status,
                itemsFetched = result.itemsFetched,
                itemsNew = result.itemsNew,
                errorMessage = result.errors.ifEmpty { null }?.joinToString("; "),
                startedAt = startedAt,
                finishedAt = clock.instant(),
            ),
        )
    }

    companion object {
        private const val NEWS = "news"
    }
}
