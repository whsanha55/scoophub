package com.scoophub.global.schedule

import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.Crawler
import com.scoophub.global.crawl.CrawlerScheduledJob
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 기동 시 잡 등록 — legacy lifespan 의 `scheduler.start()` + 각 도메인 `register_jobs`.
 * Flyway migrate 가 끝난 뒤 실행된다 (ApplicationRunner).
 */
@Component
class SchedulerStartupRunner(
    private val crawlScheduler: CrawlScheduler,
    private val jobs: ObjectProvider<ScheduledJob>,
    private val crawlers: ObjectProvider<Crawler>,
    private val crawlRunner: CrawlRunner,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val direct = jobs.orderedStream().toList()
        // 직접 구현한 ScheduledJob(news 처럼 후처리 파이프라인이 있는 도메인)이
        // 같은 "{name}_crawler" jobId 를 쓰면 어댑터가 덮어쓰지 않도록 제외
        val directJobIds = direct.map { it.jobId }.toSet()
        val adapters = crawlers.orderedStream()
            .filter { "${it.name}_crawler" !in directJobIds }
            .map { CrawlerScheduledJob(crawlRunner, it) }
            .toList()
        crawlScheduler.start(direct + adapters)
    }
}
