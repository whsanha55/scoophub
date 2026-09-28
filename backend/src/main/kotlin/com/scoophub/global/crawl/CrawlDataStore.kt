package com.scoophub.global.crawl

import com.scoophub.global.crawl.repository.CrawlDataRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant

/** 임의 객체를 JSON 으로 직렬화해 upsert (legacy `upsert_crawl_data`) */
@Component
class CrawlDataStore(
    private val repository: CrawlDataRepository,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    @Transactional
    fun upsert(category: String, purpose: String, key: String, response: Any): Long =
        upsert(category, purpose, key, response, clock.instant())

    /** dateAt: 데이터 기준시각(크롤 fetched_at) */
    @Transactional
    fun upsert(category: String, purpose: String, key: String, response: Any, dateAt: Instant): Long =
        repository.upsertJson(category, purpose, key, dateAt, jsonMapper.writeValueAsString(response))
}
