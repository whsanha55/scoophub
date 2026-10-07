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

    /** 신규 산정용 — 존재하는 key 들만 조회 */
    fun findByCategoryAndPurposeAndKeyIn(
        category: String,
        purpose: String,
        keys: Collection<String>,
    ): List<CrawlDataEntity>

    /** 자연키 최신 (weather 조회 — 시간 필터 없음) */
    fun findFirstByCategoryAndPurposeAndKeyOrderByDateAtDesc(
        category: String,
        purpose: String,
        key: String,
    ): CrawlDataEntity?

    /** 신선도 필터 — 최근 N분 이후 */
    fun findFirstByCategoryAndPurposeAndKeyAndDateAtAfterOrderByDateAtDesc(
        category: String,
        purpose: String,
        key: String,
        dateAt: Instant,
    ): CrawlDataEntity?

    /** 범위 필터 */
    fun findFirstByCategoryAndPurposeAndKeyAndDateAtBetweenOrderByDateAtDesc(
        category: String,
        purpose: String,
        key: String,
        start: Instant,
        end: Instant,
    ): CrawlDataEntity?

    /** weather forecast — weekly_forecast 가 비지 않은 최신 스냅샷 */
    @Query(
        """
        SELECT * FROM crawl_data
        WHERE category = 'weather' AND purpose = 'snapshot' AND key = :key
          AND jsonb_array_length(COALESCE(response -> 'weekly_forecast', '[]'::jsonb)) > 0
        ORDER BY date_at DESC LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findLatestSnapshotWithForecast(key: String): CrawlDataEntity?
}
