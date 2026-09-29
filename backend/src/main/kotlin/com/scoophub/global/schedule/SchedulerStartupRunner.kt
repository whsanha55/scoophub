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
        // 직접 구현한 ScheduledJob 이 있는 crawler(news 후처리, stock 분석 잡)는 어댑터 제외.
        // stock 은 stock_crawler 스케줄 row 가 없어 어댑터를 만들면 기동 실패한다.
        val directCrawlers = direct.map { it.crawler }.toSet()
        val adapters = crawlers.orderedStream()
            .filter { it.name !in directCrawlers }
            .map { CrawlerScheduledJob(crawlRunner, it) }
            .toList()
        crawlScheduler.start(direct + adapters)
    }
}
