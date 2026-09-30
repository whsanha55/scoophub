package com.scoophub.system.service

import com.scoophub.global.crawl.entity.CrawlLogEntity
import com.scoophub.global.crawl.repository.CrawlLogRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service

@Service
class CrawlLogService(private val crawlLogRepository: CrawlLogRepository) {

    fun find(crawler: String?, crawlerDetail: String?, limit: Int): List<CrawlLogEntity> = when {
        crawler != null && crawlerDetail != null ->
            crawlLogRepository.findByCrawlerAndCrawlerDetailOrderByStartedAtDesc(
                crawler,
                crawlerDetail,
                Limit.of(limit),
            )

        crawler != null ->
            crawlLogRepository.findByCrawlerOrderByStartedAtDesc(crawler, Limit.of(limit))

        crawlerDetail != null ->
            crawlLogRepository.findByCrawlerDetailOrderByStartedAtDesc(crawlerDetail, Limit.of(limit))

        else -> crawlLogRepository.findAllByOrderByStartedAtDesc(Limit.of(limit))
    }
}
