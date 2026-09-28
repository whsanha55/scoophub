package com.scoophub.kal

import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/** legacy `kal_bonus/crawler.py` — Crawler 인터페이스 어댑터. 크롤 로직은 [KalBonusScraper] */
@Component
class KalBonusCrawler(private val routesLoader: KalRoutesLoader, private val scraper: KalBonusScraper) : Crawler {
    override val name = "kal_bonus"
    override val detail = "보너스 좌석 현황 (대상: crawl_sources kal_bonus config)"

    // Akamai 가 headless Chrome 을 차단 → 항상 headful (docker 는 xvfb 로 구동)
    private val headless = false

    override fun fetch(): CrawlResult {
        val targets = routesLoader.load()
        val counts = scraper.fetchAndStore(targets, headless)
        return CrawlResult(itemsFetched = counts.targets, itemsNew = counts.stored)
    }
}
