package com.scoophub.global.schedule.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import java.time.Instant

/** V12__crawl_config.sql — 크롤러 도메인 파라미터 (categories, feeds 등) */
@Entity
@Table(name = "crawl_config")
class CrawlConfigEntity(
    @Id
    val crawler: String,
    @JdbcTypeCode(SqlTypes.JSON)
    val params: JsonNode,
    @Column(insertable = false, updatable = false)
    val updatedAt: Instant,
)
