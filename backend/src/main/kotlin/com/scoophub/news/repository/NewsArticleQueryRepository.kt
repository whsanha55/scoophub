package com.scoophub.news.repository

import com.scoophub.news.vo.AlpacaArticlePage
import com.scoophub.news.vo.AlpacaArticleRow
import com.scoophub.news.vo.ArticleAssessment
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class NewsArticleQueryRepository(private val jdbc: JdbcClient) {
    private val mapper = RowMapper { rs, _ ->
        AlpacaArticleRow(
            id = rs.getLong("id"), source = rs.getString("source"), headline = rs.getString("headline"),
            summary = rs.getString("summary"), content = rs.getString("content"), author = rs.getString("author"),
            url = rs.getString("url"), symbols = (rs.getArray("symbols").array as Array<*>).map { it.toString() },
            publishedAt = rs.getTimestamp("published_at").toInstant(),
            sourceUpdatedAt = rs.getTimestamp("source_updated_at").toInstant(),
            status = rs.getString("status"), attempts = rs.getInt("attempts"),
            importance = rs.getObject("importance", Integer::class.java)?.toInt(),
            category = rs.getString("category"), summaryKo = rs.getString("summary_ko"),
            decisionReason = rs.getString("decision_reason"),
            createdAt = rs.getTimestamp(
                "created_at",
            ).toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    fun upsert(article: AlpacaArticleRow) {
        jdbc.sql(
            """
            INSERT INTO news_article (id, source, headline, summary, content, author, url, symbols, published_at, source_updated_at)
            VALUES (:id, :source, :headline, :summary, :content, :author, :url, :symbols::text[], :published, :updated)
            ON CONFLICT (id) DO UPDATE SET source = EXCLUDED.source, headline = EXCLUDED.headline,
                summary = EXCLUDED.summary, content = EXCLUDED.content, author = EXCLUDED.author,
                url = EXCLUDED.url, symbols = EXCLUDED.symbols, source_updated_at = EXCLUDED.source_updated_at, updated_at = NOW()
            WHERE news_article.source_updated_at < EXCLUDED.source_updated_at
            """.trimIndent(),
        ).param("id", article.id).param("source", article.source).param("headline", article.headline)
            .param("summary", article.summary).param("content", article.content).param("author", article.author)
            .param("url", article.url).param("symbols", article.symbols.toTypedArray())
            .param(
                "published",
                Timestamp.from(article.publishedAt),
            ).param("updated", Timestamp.from(article.sourceUpdatedAt)).update()
    }

    fun findPending(now: Instant): List<AlpacaArticleRow> = jdbc.sql(
        "SELECT * FROM news_article WHERE status = 'pending' AND next_attempt_at <= :now ORDER BY published_at, id LIMIT 20",
    ).param("now", Timestamp.from(now)).query(mapper).list()

    fun findById(id: Long): AlpacaArticleRow? = jdbc.sql("SELECT * FROM news_article WHERE id = :id")
        .param("id", id).query(mapper).optional().orElse(null)

    fun findArticles(
        from: Instant,
        to: Instant?,
        category: String?,
        minImportance: Int?,
        symbol: String?,
        limit: Int,
        page: Int,
    ): AlpacaArticlePage {
        val conditions = mutableListOf("published_at >= :from")
        val params = mutableMapOf<String, Any>("from" to Timestamp.from(from))
        if (to != null) {
            conditions += "published_at <= :to"
            params["to"] = Timestamp.from(to)
        }
        if (!category.isNullOrBlank()) {
            conditions += "category = :category"
            params["category"] = category
        }
        if (minImportance != null) {
            conditions += "importance >= :importance"
            params["importance"] = minImportance
        }
        if (!symbol.isNullOrBlank()) {
            conditions += "symbols @> ARRAY[:symbol]::text[]"
            params["symbol"] = symbol
        }
        val where = conditions.joinToString(" AND ")
        val total = jdbc.sql(
            "SELECT COUNT(*) FROM news_article WHERE $where",
        ).params(params).query(Int::class.java).single()
        val articles = jdbc.sql(
            "SELECT * FROM news_article WHERE $where ORDER BY published_at DESC, id DESC LIMIT :limit OFFSET :offset",
        )
            .params(params).param("limit", limit).param("offset", (page.toLong() - 1) * limit).query(mapper).list()
        return AlpacaArticlePage(total, articles)
    }

    fun saveAssessment(id: Long, assessment: ArticleAssessment) {
        jdbc.sql(
            "UPDATE news_article SET importance = :importance, category = :category, summary_ko = :summary, updated_at = NOW() WHERE id = :id AND status = 'pending'",
        )
            .param(
                "id",
                id,
            ).param(
                "importance",
                assessment.importance,
            ).param("category", assessment.category).param("summary", assessment.summaryKo).update()
    }

    fun decide(id: Long, status: String, reason: String, now: Instant) {
        jdbc.sql(
            "UPDATE news_article SET status = :status, decision_reason = :reason, decided_at = :now, updated_at = NOW() WHERE id = :id AND status = 'pending'",
        )
            .param("id", id).param("status", status).param("reason", reason).param("now", Timestamp.from(now)).update()
    }

    fun retry(id: Long, attempts: Int, nextAttempt: Instant, reason: String) {
        jdbc.sql(
            "UPDATE news_article SET attempts = :attempts, next_attempt_at = :next, decision_reason = :reason, updated_at = NOW() WHERE id = :id AND status = 'pending'",
        )
            .param(
                "id",
                id,
            ).param("attempts", attempts).param("next", Timestamp.from(nextAttempt)).param("reason", reason).update()
    }

    fun findWatchlistSymbols(): Set<String> = jdbc.sql("SELECT ticker FROM stock_watchlist WHERE is_active = true")
        .query(String::class.java).list().filterNotNull().map { it.uppercase() }.toSet()

    fun findBurstSymbols(since: Instant, now: Instant, threshold: Int): List<String> = jdbc.sql(
        """
        SELECT symbol FROM news_article, LATERAL unnest(symbols) AS symbol
        WHERE published_at >= :since AND published_at <= :now AND importance >= 3
        GROUP BY symbol HAVING COUNT(DISTINCT id) >= :threshold ORDER BY symbol
        """.trimIndent(),
    ).param(
        "since",
        Timestamp.from(since),
    ).param("now", Timestamp.from(now)).param("threshold", threshold).query(String::class.java).list().filterNotNull()

    fun findBurstArticles(symbol: String, since: Instant, now: Instant): List<AlpacaArticleRow> = jdbc.sql(
        "SELECT * FROM news_article WHERE symbols @> ARRAY[:symbol]::text[] AND published_at >= :since AND published_at <= :now AND importance >= 3 ORDER BY published_at, id LIMIT 10",
    ).param(
        "symbol",
        symbol,
    ).param("since", Timestamp.from(since)).param("now", Timestamp.from(now)).query(mapper).list()

    fun hasRecentBurst(symbol: String, since: Instant): Boolean = jdbc.sql(
        "SELECT EXISTS(SELECT 1 FROM notify_log WHERE payload_key LIKE 'news:burst:%' AND split_part(payload_key, ':', 3) = :symbol AND status = 'success' AND sent_at > :since)",
    ).param("symbol", symbol).param("since", Timestamp.from(since)).query(Boolean::class.java).single()
}
