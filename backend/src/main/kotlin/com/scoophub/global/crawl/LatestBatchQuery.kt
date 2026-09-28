package com.scoophub.global.crawl

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

/**
 * legacy batch 도메인 router 의 최신 배치 조회 공통 (hacker_news 등).
 * 최신 `fetched_at` 배치만 필터·정렬해 반환한다. 필드명은 코드 상수만 허용(사용자 입력은 파라미터로만).
 */
@Component
class LatestBatchQuery(private val jdbcClient: JdbcClient, private val jsonMapper: JsonMapper) {
    data class BatchRow(val id: Long, val key: String, val dateAt: Instant, val response: JsonNode)

    sealed interface Filter {
        val value: Any
    }

    data class TextEq(val field: String, override val value: String) : Filter

    /** ILIKE — value 에 와일드카드 포함(예: %kw%) */
    data class TextLike(val field: String, override val value: String) : Filter

    /** JSONB 배열 포함 — value 는 배열 엘리먼트 하나(예: tags @> '"["python"]"'::jsonb') */
    data class JsonArrayContains(val field: String, override val value: String) : Filter

    data class IntGte(val field: String, override val value: Int) : Filter

    data class TimestamptzGte(val field: String, override val value: Instant) : Filter

    sealed interface SortKey {
        data class IntDesc(val field: String) : SortKey

        data object DateAtDesc : SortKey
    }

    fun latestFetchedAt(category: String, purpose: String, baseFilters: List<Filter> = emptyList()): Instant? {
        val params = MapSqlParameterSource()
            .addValue("category", category)
            .addValue("purpose", purpose)
        var sql = """
            SELECT MAX((response ->> 'fetched_at')::timestamptz) FROM crawl_data
            WHERE category = :category AND purpose = :purpose
        """
        baseFilters.forEachIndexed { i, f ->
            params.addValue("f$i", f.value)
            sql += when (f) {
                is TextEq -> " AND response ->> '${f.field}' = :f$i"
                is TextLike -> " AND response ->> '${f.field}' ILIKE :f$i"
                is JsonArrayContains -> " AND response -> '${f.field}' @> :f$i::jsonb"
                is IntGte -> " AND (response ->> '${f.field}')::int >= :f$i"
                is TimestamptzGte -> " AND (response ->> '${f.field}')::timestamptz >= :f$i"
            }
        }
        return jdbcClient.sql(sql)
            .paramSource(params)
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .optional()
            .orElse(null)
    }

    fun fetch(
        category: String,
        purpose: String,
        latest: Instant,
        filters: List<Filter> = emptyList(),
        sortKey: SortKey = SortKey.IntDesc("score"),
        limit: Int,
    ): List<BatchRow> {
        val params = MapSqlParameterSource()
            .addValue("category", category)
            .addValue("purpose", purpose)
            .addValue("latest", Timestamp.from(latest))
            .addValue("limit", limit)

        var sql = """
            SELECT id, key, date_at, response FROM crawl_data
            WHERE category = :category AND purpose = :purpose
              AND (response ->> 'fetched_at')::timestamptz = :latest
        """
        filters.forEachIndexed { i, f ->
            params.addValue("f$i", f.value)
            sql += when (f) {
                is TextEq -> " AND response ->> '${f.field}' = :f$i"
                is TextLike -> " AND response ->> '${f.field}' ILIKE :f$i"
                is JsonArrayContains -> " AND response -> '${f.field}' @> :f$i::jsonb"
                is IntGte -> " AND (response ->> '${f.field}')::int >= :f$i"
                is TimestamptzGte -> " AND (response ->> '${f.field}')::timestamptz >= :f$i"
            }
        }
        sql += when (sortKey) {
            is SortKey.IntDesc -> " ORDER BY (response ->> '${sortKey.field}')::int DESC NULLS LAST"
            SortKey.DateAtDesc -> " ORDER BY date_at DESC NULLS LAST"
        }
        sql += " LIMIT :limit"

        return jdbcClient.sql(sql)
            .paramSource(params)
            .query { rs, _ ->
                BatchRow(
                    id = rs.getLong("id"),
                    key = rs.getString("key"),
                    dateAt = rs.getTimestamp("date_at").toInstant(),
                    response = jsonMapper.readTree(rs.getString("response")),
                )
            }
            .list()
    }
}
