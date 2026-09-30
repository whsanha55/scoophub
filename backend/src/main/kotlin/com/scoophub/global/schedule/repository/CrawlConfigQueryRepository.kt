package com.scoophub.global.schedule.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CrawlConfigQueryRepository(private val jdbcClient: JdbcClient) {

    /** params JSONB 병합 (최상위 키 단위) */
    fun mergeParams(crawler: String, patchJson: String) {
        jdbcClient.sql(
            "UPDATE crawl_config SET params = params || CAST(:patch AS jsonb), updated_at = now() WHERE crawler = :crawler",
        )
            .param("patch", patchJson)
            .param("crawler", crawler)
            .update()
    }
}
