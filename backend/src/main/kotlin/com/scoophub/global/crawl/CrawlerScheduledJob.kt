package com.scoophub.global.crawl

import com.scoophub.global.schedule.ScheduledJob
import tools.jackson.databind.JsonNode

/**
 * `Crawler` 빈을 스케줄 잡으로 등록하는 어댑터. seed 의 크롤 잡 job_id 는
 * 전부 `{crawler}_crawler` 패턴이라 자동 매핑된다.
 * params 소비가 필요한 도메인(news, hacker_news 등)은 `ScheduledJob` 을 직접 구현한다.
 */
class CrawlerScheduledJob(private val runner: CrawlRunner, private val delegate: Crawler) : ScheduledJob {
    override val crawler: String
        get() = delegate.name

    override val jobId: String
        get() = "${delegate.name}_crawler"

    override fun run(params: JsonNode) {
        runner.run(delegate)
    }
}
