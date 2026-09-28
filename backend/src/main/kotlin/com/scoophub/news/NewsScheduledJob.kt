package com.scoophub.news

import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.notify.CrawlNotifyDispatcher
import com.scoophub.global.schedule.ScheduledJob
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

private val log = KotlinLogging.logger {}

/**
 * legacy `news/scheduler.py` 의 _run_news_crawl — 크롤 → LLM dedup → 요약 → 발신 파이프라인.
 * news 는 CrawlCompletedEvent 발신을 스킵(CrawlRunner)하고 요약 완료 후 여기서 발신한다.
 */
@Component
class NewsScheduledJob(
    private val crawlRunner: CrawlRunner,
    private val newsCrawler: NewsCrawler,
    private val dedup: NewsDedup,
    private val summarizer: NewsSummarizer,
    private val notify: CrawlNotifyDispatcher,
) : ScheduledJob {
    override val crawler = "news"
    override val jobId = "news_crawler"

    override fun run(params: JsonNode) {
        val dedupWindowHours = params["dedup_window_hours"]?.asInt(24) ?: 24

        val crawlResult = crawlRunner.run(newsCrawler)

        // LLM dedup: 신규 삽입 기사 중 중복 판별
        val newIds = crawlResult?.newArticleIds?.map { it.toInt() } ?: emptyList()
        if (newIds.isNotEmpty()) {
            try {
                val deduped = dedup.llmDedup(newIds, dedupWindowHours)
                if (deduped > 0) {
                    log.info { "LLM dedup marked $deduped duplicates" }
                }
            } catch (e: Exception) {
                log.error { "LLM dedup failed: ${e.message}" }
            }
        }

        // 요약 (중복 아닌 기사)
        try {
            val result = summarizer.summarizeIncomplete()
            if (result.total > 0) {
                log.info { "Summarized $result" }
            }
        } catch (e: Exception) {
            log.error { "Summarization failed: ${e.message}" }
        }

        // 발신 — summarizer 완료 후 (importance>=4 갱신됨)
        if (crawlResult != null) {
            try {
                notify.dispatch("news", "rss", crawlResult)
            } catch (e: Exception) {
                log.error { "news notify failed: ${e.message}" }
            }
        }
    }
}
