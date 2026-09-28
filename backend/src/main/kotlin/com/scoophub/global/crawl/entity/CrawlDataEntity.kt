package com.scoophub.global.crawl.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * V8__crawl_data.sql — "크롤 → 최신 응답 저장 → 최신 조회" 패턴 공통 캐시.
 * 도메인은 category/purpose/key 조합만 정해 재사용한다. 쓰기는 [com.scoophub.global.crawl.CrawlDataStore] 로만.
 */
@Entity
@Table(name = "crawl_data")
class CrawlDataEntity(
    /** GENERATED ALWAYS AS IDENTITY — 앱에서 대입하지 않음 */
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
