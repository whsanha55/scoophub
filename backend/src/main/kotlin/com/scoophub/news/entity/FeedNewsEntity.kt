package com.scoophub.news.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** V3__feed.sql feed_news — 뉴스 기사 */
@Entity
@Table(name = "feed_news")
class FeedNewsEntity(
    @Id
    val id: Int,
    val source: String,
    val category: String?,
    val title: String,
    val summary: String?,
    val url: String,
    val normalizedUrl: String?,
    val publishedAt: Instant?,
    val importance: Short,
    val summaryStatus: String,
    val duplicated: Boolean,
    val duplicatedNewsId: Int?,
    @Column(insertable = false, updatable = false)
    val createdAt: Instant,
    @Column(insertable = false, updatable = false)
    val updatedAt: Instant,
)
