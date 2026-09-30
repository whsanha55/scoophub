package com.scoophub.kal.repository

import com.scoophub.kal.KalConfig
import com.scoophub.kal.dto.KalBonusCondition
import com.scoophub.kal.vo.KalBonusRow
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper

@Repository
class KalBonusQueryRepository(private val jdbcClient: JdbcClient, private val jsonMapper: JsonMapper) {
    fun findMonths(): List<String> = jdbcClient.sql(
        "SELECT DISTINCT substring(key from 1 for 6) AS ym FROM crawl_data " +
            "WHERE category = :category AND purpose = :purpose ORDER BY 1",
    )
        .param("category", KalConfig.CATEGORY)
        .param("purpose", KalConfig.PURPOSE)
        .query { rs, _ -> rs.getString("ym") }
        .list()

    fun findAll(condition: KalBonusCondition): List<KalBonusRow> {
        val params = MapSqlParameterSource()
            .addValue("category", KalConfig.CATEGORY)
            .addValue("purpose", KalConfig.PURPOSE)
            .addValue("limit", condition.limit)
        val where = mutableListOf("category = :category", "purpose = :purpose")
        condition.month?.let {
            // key 포맷: {YYYYMM}-{DEPARTURE}-{ARRIVAL} → 월은 접두사 매칭
            where += "key LIKE :monthPrefix"
            params.addValue("monthPrefix", "$it%")
        }
        condition.arrival?.let {
            // 도착은 접미사 매칭
            where += "key LIKE :arrivalSuffix"
            params.addValue("arrivalSuffix", "%-${KalConfig.DEPARTURE}-$it")
        }

        return jdbcClient.sql(
            "SELECT key, date_at, updated_at, response FROM crawl_data " +
                "WHERE ${where.joinToString(" AND ")} ORDER BY date_at DESC LIMIT :limit",
        )
            .paramSource(params)
            .query { rs, _ ->
                KalBonusRow(
                    key = rs.getString("key"),
                    dateAt = rs.getTimestamp("date_at")?.toInstant(),
                    updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
                    response = jsonMapper.readTree(rs.getString("response")),
                )
            }
            .list()
    }
}
