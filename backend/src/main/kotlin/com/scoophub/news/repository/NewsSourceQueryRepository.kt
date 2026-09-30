package com.scoophub.news.repository

import com.scoophub.news.vo.NewsSourceRow
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper

/** crawl_sources(crawler='news') — legacy `news/sources_router.py` 쿼리 */
@Repository
class NewsSourceQueryRepository(private val jdbcClient: JdbcClient, private val jsonMapper: JsonMapper) {
    private val rowMapper = RowMapper { rs, _ ->
        NewsSourceRow(
            id = rs.getInt("id"),
            crawler = rs.getString("crawler"),
            name = rs.getString("name"),
            url = rs.getString("url"),
            active = rs.getBoolean("active"),
            config = jsonMapper.readTree(rs.getString("config")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    fun findAll(activeOnly: Boolean): List<NewsSourceRow> {
        val where = if (activeOnly) "AND active = true" else ""
        return jdbcClient.sql(
            """
            SELECT id, crawler, name, url, active, config, created_at, updated_at
            FROM crawl_sources WHERE crawler = 'news' $where ORDER BY id
            """.trimIndent(),
        )
            .query(rowMapper)
            .list()
    }

    fun findById(id: Int): NewsSourceRow? = jdbcClient.sql(
        "SELECT id, crawler, name, url, active, config, created_at, updated_at FROM crawl_sources WHERE id = :id",
    )
        .param("id", id)
        .query(rowMapper)
        .optional()
        .orElse(null)

    /** URL 중복 시 DuplicateKeyException */
    fun insert(name: String, url: String, active: Boolean): Int = jdbcClient.sql(
        """
        INSERT INTO crawl_sources (crawler, name, url, active, config)
        VALUES ('news', :name, :url, :active, '{}'::jsonb) RETURNING id
        """.trimIndent(),
    )
        .param("name", name)
        .param("url", url)
        .param("active", active)
        .query { rs, _ -> rs.getInt(1) }
        .single()

    /** null 필드는 유지. 변경할 필드가 없으면 false */
    fun update(id: Int, name: String?, url: String?, active: Boolean?): Boolean {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        name?.let {
            sets += "name = :name"
            params["name"] = it
        }
        url?.let {
            sets += "url = :url"
            params["url"] = it
        }
        active?.let {
            sets += "active = :active"
            params["active"] = it
        }
        if (sets.isEmpty()) {
            return false
        }
        jdbcClient.sql("UPDATE crawl_sources SET ${sets.joinToString(", ")}, updated_at = now() WHERE id = :id")
            .param("id", id)
            .params(params)
            .update()
        return true
    }

    fun delete(id: Int): Int = jdbcClient.sql("DELETE FROM crawl_sources WHERE id = :id AND crawler = 'news'")
        .param("id", id)
        .update()
}
