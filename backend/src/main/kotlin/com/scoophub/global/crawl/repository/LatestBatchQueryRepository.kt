package com.scoophub.global.crawl.repository

import com.scoophub.global.crawl.vo.BatchFilter
import com.scoophub.global.crawl.vo.BatchFilter.IntGte
import com.scoophub.global.crawl.vo.BatchFilter.JsonArrayContains
import com.scoophub.global.crawl.vo.BatchFilter.TextEq
import com.scoophub.global.crawl.vo.BatchFilter.TextLike
import com.scoophub.global.crawl.vo.BatchFilter.TimestamptzGte
import com.scoophub.global.crawl.vo.BatchRow
import com.scoophub.global.crawl.vo.BatchSortKey
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

/**
 * legacy batch 도메인 router 의 최신 배치 조회 공통 (hacker_news 등).
 * 최신 `fetched_at` 배치만 필터·정렬해 반환한다. 필드명은 코드 상수만 허용(사용자 입력은 파라미터로만).
 */
@Repository
class LatestBatchQueryRepository(private val jdbcClient: JdbcClient, private val jsonMapper: JsonMapper) {
    fun latestFetchedAt(category: String, purpose: String, baseFilters: List<BatchFilter> = emptyList()): Instant? {
        val params = MapSqlParameterSource()
            .addValue("category", category)
            .addValue("purpose", purpose)
        var sql = """
            SELECT MAX((response ->> 'fetched_at')::timestamptz) FROM crawl_data
            WHERE category = :category AND purpose = :purpose
        """
        baseFilters.forEachIndexed { i, f ->
            params.addValue("f$i", paramValue(f))
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
        filters: List<BatchFilter> = emptyList(),
        sortKey: BatchSortKey = BatchSortKey.IntDesc("score"),
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
            params.addValue("f$i", paramValue(f))
            sql += when (f) {
                is TextEq -> " AND response ->> '${f.field}' = :f$i"
                is TextLike -> " AND response ->> '${f.field}' ILIKE :f$i"
                is JsonArrayContains -> " AND response -> '${f.field}' @> :f$i::jsonb"
                is IntGte -> " AND (response ->> '${f.field}')::int >= :f$i"
                is TimestamptzGte -> " AND (response ->> '${f.field}')::timestamptz >= :f$i"
            }
        }
        sql += when (sortKey) {
            is BatchSortKey.IntDesc -> " ORDER BY (response ->> '${sortKey.field}')::int DESC NULLS LAST"
            is BatchSortKey.LongDesc -> " ORDER BY (response ->> '${sortKey.field}')::bigint DESC NULLS LAST"
            BatchSortKey.DateAtDesc -> " ORDER BY date_at DESC NULLS LAST"
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

    /** JsonArrayContains 는 원소 하나를 JSON 배열로 직렬화한다 (사용자 입력의 따옴표 등 이스케이프) */
    private fun paramValue(f: BatchFilter): Any =
        if (f is JsonArrayContains) jsonMapper.writeValueAsString(listOf(f.value)) else f.value
}
