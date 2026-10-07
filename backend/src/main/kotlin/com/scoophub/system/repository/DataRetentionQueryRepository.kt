package com.scoophub.system.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** 보존 기간이 지난 로그·뉴스 삭제. 각 메서드는 삭제한 행 수를 반환한다 */
@Repository
class DataRetentionQueryRepository(private val jdbcClient: JdbcClient) {

    fun deleteCrawlLogsBefore(cutoff: Instant): Int =
        jdbcClient.sql("DELETE FROM crawl_logs WHERE started_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update()

    fun deleteNotifyLogsBefore(cutoff: Instant): Int = jdbcClient.sql("DELETE FROM notify_log WHERE sent_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update()

    /** 처리 대기(pending) 기사는 남긴다 */
    fun deleteNewsArticlesBefore(cutoff: Instant): Int = jdbcClient.sql(
        "DELETE FROM news_article WHERE published_at < :cutoff AND status <> 'pending'",
    )
        .param("cutoff", Timestamp.from(cutoff))
        .update()
}
