package com.scoophub.global.crawl.repository

import com.scoophub.global.crawl.entity.CrawlDataEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface CrawlDataRepository : JpaRepository<CrawlDataEntity, Long> {

    /** 동일 (category, purpose, key) 재크롤 시 최신 응답으로 덮어쓴다. row id 반환 */
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
    fun findFirstByCategoryAndPurposeOrderByDateAtDesc(category: String, purpose: String): CrawlDataEntity?

    /** notify enrich 용 — 최근 갱신순 배치 */
    fun findFirst50ByCategoryAndPurposeOrderByUpdatedAtDesc(category: String, purpose: String): List<CrawlDataEntity>

    /** 자연키 직접 조회 */
    fun findByCategoryAndPurposeAndKey(category: String, purpose: String, key: String): CrawlDataEntity?

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
    fun findByPath(
        category: String,
        purpose: String,
        path: String,
        value: String,
        limit: Int = 100,
    ): List<CrawlDataEntity>
}
