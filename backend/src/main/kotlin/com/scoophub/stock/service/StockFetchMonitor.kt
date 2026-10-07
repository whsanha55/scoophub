package com.scoophub.stock.service

import com.scoophub.global.crawl.entity.CrawlLogEntity
import com.scoophub.global.crawl.enums.CrawlStatusEnum
import com.scoophub.global.crawl.repository.CrawlLogRepository
import com.scoophub.global.notify.NotifyCard
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.stock.vo.FetchOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

private val log = KotlinLogging.logger {}

/**
 * 시세 수집 잡 결과를 crawl_logs 에 남기고, 전 종목 실패나 2회 연속 실패면 알린다.
 * 수집 실패가 로그에만 남아 장애를 9일간 몰랐던 일(#239)의 재발 방지.
 */
@Service
class StockFetchMonitor(
    private val crawlLogRepository: CrawlLogRepository,
    private val notifyRouter: NotifyRouter,
    private val clock: Clock,
) {
    fun record(jobId: String, startedAt: Instant, outcome: FetchOutcome) {
        if (outcome.total == 0) {
            return
        }
        val status = when {
            outcome.failures.isEmpty() -> CrawlStatusEnum.SUCCESS
            outcome.failures.size == outcome.total -> CrawlStatusEnum.ERROR
            else -> CrawlStatusEnum.PARTIAL
        }
        val previous = crawlLogRepository
            .findByCrawlerAndCrawlerDetailOrderByStartedAtDesc(CRAWLER, jobId, Limit.of(1))
            .firstOrNull()
        crawlLogRepository.save(
            CrawlLogEntity(
                crawler = CRAWLER,
                crawlerDetail = jobId,
                status = status,
                itemsFetched = outcome.total - outcome.failures.size,
                itemsNew = outcome.saved,
                errorMessage = outcome.failures.entries.joinToString("; ") {
                    "${it.key}: ${it.value}"
                }.ifEmpty { null },
                startedAt = startedAt,
                finishedAt = clock.instant(),
            ),
        )
        val isConsecutive = previous != null && previous.status != CrawlStatusEnum.SUCCESS
        if (status == CrawlStatusEnum.ERROR || (status == CrawlStatusEnum.PARTIAL && isConsecutive)) {
            alert(jobId, outcome)
        }
    }

    private fun alert(jobId: String, outcome: FetchOutcome) {
        val today = clock.instant().atZone(SEOUL).toLocalDate()
        val reasons = outcome.failures.values
            .groupingBy { it }
            .eachCount()
            .entries
            .joinToString(", ") { "${it.key} ×${it.value}" }
        val text = "⚠️ <b>시세 수집 실패</b> — $jobId\n" +
            "실패 ${outcome.failures.size}/${outcome.total} 종목\n" +
            NotifyCard.escapeHtml(reasons) + "\n" +
            NotifyCard.escapeHtml(outcome.failures.keys.joinToString(", "))
        try {
            notifyRouter.dispatch(CRAWLER, PURPOSE, "stock:$PURPOSE:$jobId:$today", NotifyMessage(text))
        } catch (e: Exception) {
            log.warn { "fetch alert dispatch failed (job=$jobId): ${e.message}" }
        }
    }

    companion object {
        private const val CRAWLER = "stock"
        const val PURPOSE = "fetch-alert"
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
