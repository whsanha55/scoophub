package com.scoophub.news.repository

import com.scoophub.news.vo.NewsArticlePage
import com.scoophub.news.vo.NewsArticleRow
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

private val log = KotlinLogging.logger {}

/** legacy `news/router.py` GET /news 쿼리 — 시간 필터 + category/min_importance + 카운트 */
@Repository
class NewsQueryRepository(private val jdbcClient: JdbcClient) {
    fun findArticles(
        minutes: Int?,
        from: Instant?,
        to: Instant?,
        category: String?,
        minImportance: Int?,
        limit: Int,
    ): NewsArticlePage {
        val params = MapSqlParameterSource()
        val conditions = mutableListOf("duplicated = false")

        when {
            minutes != null -> conditions += "created_at >= now() - make_interval(mins => :minutes)"

            from != null && to != null -> {
                conditions += "created_at BETWEEN :fr AND :to"
                params.addValue("fr", Timestamp.from(from))
                params.addValue("to", Timestamp.from(to))
            }

            else -> conditions += "created_at >= now() - make_interval(mins => 30)" // 기본 최근 30분
        }
        category?.takeIf { it.isNotEmpty() }?.let {
            conditions += "category = :category"
            params.addValue("category", it)
        }
        minImportance?.let {
            conditions += "importance >= :minImportance"
            params.addValue("minImportance", it)
        }
        if (minutes != null) {
            params.addValue("minutes", minutes)
        }

        val where = conditions.joinToString(" AND ")
        val total = jdbcClient.sql("SELECT COUNT(*) FROM feed_news WHERE $where")
            .paramSource(params)
            .query { rs, _ -> rs.getInt(1) }
            .single()
        val articles = jdbcClient.sql(
            "SELECT * FROM feed_news WHERE $where ORDER BY created_at DESC LIMIT :limit",
        )
            .paramSource(params.addValue("limit", limit))
            .query { rs, _ ->
                NewsArticleRow(
                    id = rs.getInt("id"),
                    source = rs.getString("source"),
                    category = rs.getString("category"),
                    title = rs.getString("title"),
                    summary = rs.getString("summary"),
                    url = rs.getString("url"),
                    normalizedUrl = rs.getString("normalized_url"),
                    publishedAt = rs.getTimestamp("published_at")?.toInstant(),
                    importance = rs.getInt("importance"),
                    summaryStatus = rs.getString("summary_status"),
                    duplicated = rs.getBoolean("duplicated"),
                    duplicatedNewsId = rs.getObject("duplicated_news_id") as Int?,
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
            .list()
        log.info { "get_news 완료 - total=$total, returned=${articles.size}" }
        return NewsArticlePage(total, articles)
    }

    fun findById(articleId: Int): NewsArticleRow? =
        jdbcClient.sql("SELECT * FROM feed_news WHERE id = :id AND duplicated = false")
            .param("id", articleId)
            .query { rs, _ ->
                NewsArticleRow(
                    id = rs.getInt("id"),
                    source = rs.getString("source"),
                    category = rs.getString("category"),
                    title = rs.getString("title"),
                    summary = rs.getString("summary"),
                    url = rs.getString("url"),
                    normalizedUrl = rs.getString("normalized_url"),
                    publishedAt = rs.getTimestamp("published_at")?.toInstant(),
                    importance = rs.getInt("importance"),
                    summaryStatus = rs.getString("summary_status"),
                    duplicated = rs.getBoolean("duplicated"),
                    duplicatedNewsId = rs.getObject("duplicated_news_id") as Int?,
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
            .optional()
            .orElse(null)
}
