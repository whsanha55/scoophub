package com.scoophub.system.service

import com.scoophub.system.repository.DataRetentionQueryRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

@Service
class DataRetentionService(private val repository: DataRetentionQueryRepository, private val clock: Clock) {

    @Transactional
    fun purge() {
        val cutoff = clock.instant().minus(RETENTION)
        val crawlLogs = repository.deleteCrawlLogsBefore(cutoff)
        val notifyLogs = repository.deleteNotifyLogsBefore(cutoff)
        val articles = repository.deleteNewsArticlesBefore(cutoff)
        log.info { "Data retention purge: crawl_logs=$crawlLogs notify_log=$notifyLogs news_article=$articles" }
    }

    companion object {
        val RETENTION: Duration = Duration.ofDays(90)
    }
}
