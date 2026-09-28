package com.scoophub.news.repository

import com.scoophub.news.entity.FeedNewsEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface FeedNewsRepository : JpaRepository<FeedNewsEntity, Int> {

    /** URL 정규화 키 충돌 시 무시. 삽입되면 id, 충돌이면 null */
    @Query(
        """
        INSERT INTO feed_news (source, title, summary, url, normalized_url, published_at)
        VALUES (:source, :title, :summary, :url, :normalizedUrl, :publishedAt)
        ON CONFLICT (normalized_url) DO NOTHING
        RETURNING id
        """,
        nativeQuery = true,
    )
    fun insertOnConflictIgnore(
        @Param("source") source: String,
        @Param("title") title: String,
        @Param("summary") summary: String?,
        @Param("url") url: String,
        @Param("normalizedUrl") normalizedUrl: String,
        @Param("publishedAt") publishedAt: Instant?,
    ): Int?
}
