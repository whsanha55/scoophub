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
        val all = jobs.orderedStream().toList() +
            crawlers.orderedStream().map { CrawlerScheduledJob(crawlRunner, it) }.toList()
        crawlScheduler.start(all)
    }
}
