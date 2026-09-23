package com.scoophub.crawldata

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * V8__crawl_data.sql — "크롤 → 최신 응답 저장 → 최신 조회" 패턴 공통 캐시.
 * 도메인은 category/purpose/key 조합만 정해 재사용한다. 쓰기는 [CrawlDataStore.upsert] 로만.
 */
@Entity
@Table(name = "crawl_data")
class CrawlData(
    @Id
    val id: Long,
    val category: String,
    val purpose: String,
    val key: String,
    val dateAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON)
    val response: JsonNode,
    @Column(insertable = false, updatable = false)
    val updatedAt: Instant,
)

interface CrawlDataRepository : JpaRepository<CrawlData, Long> {

    /** 동일 (category, purpose, key) 재크롤 시 최신 응답으로 덮어쓴다. row id 반환 */
    @Transactional
    @Query(
        """
        INSERT INTO crawl_data (category, purpose, key, date_at, response)
        VALUES (:category, :purpose, :key, :dateAt, CAST(:response AS jsonb))
        ON CONFLICT (category, purpose, key)
        DO UPDATE SET response   = EXCLUDED.response,
                      date_at    = EXCLUDED.date_at,
                      updated_at = now()
        RETURNING id
        """,
        nativeQuery = true,
    )
    fun upsertJson(category: String, purpose: String, key: String, dateAt: Instant, response: String): Long

    /** 특정 category/purpose 의 최신 1건 */
    fun findFirstByCategoryAndPurposeOrderByDateAtDesc(category: String, purpose: String): CrawlData?

    /** 자연키 직접 조회 */
    fun findByCategoryAndPurposeAndKey(category: String, purpose: String, key: String): CrawlData?

    /** response JSONB 경로 값으로 필터. path 예: `flightList` / `meta.score` */
    @Query(
        """
        SELECT * FROM crawl_data
        WHERE category = :category AND purpose = :purpose
          AND response #>> string_to_array(:path, '.') = :value
        ORDER BY date_at DESC LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findByPath(category: String, purpose: String, path: String, value: String, limit: Int = 100): List<CrawlData>
}

/** 임의 객체를 JSON 으로 직렬화해 upsert (legacy `upsert_crawl_data`) */
@Component
class CrawlDataStore(
    private val repository: CrawlDataRepository,
    private val jsonMapper: JsonMapper,
) {
    fun upsert(category: String, purpose: String, key: String, response: Any, dateAt: Instant = Instant.now()): Long =
        repository.upsertJson(category, purpose, key, dateAt, jsonMapper.writeValueAsString(response))
}
