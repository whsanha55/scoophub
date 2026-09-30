package com.scoophub.stock.repository

import com.scoophub.stock.dto.WatchlistUpdateIn
import com.scoophub.stock.vo.WatchlistRow
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class StockWatchlistQueryRepository(private val jdbcClient: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        WatchlistRow(
            id = rs.getInt("id"),
            ticker = rs.getString("ticker"),
            exchange = rs.getString("exchange"),
            name = rs.getString("name"),
            memo = rs.getString("memo"),
            isActive = rs.getBoolean("is_active"),
            group = rs.getString("group"),
            addedAt = rs.getTimestamp("added_at").toInstant(),
        )
    }

    /** (ticker) 중복 시 DataIntegrityViolationException */
    fun insert(ticker: String, exchange: String, name: String, memo: String?, group: String): WatchlistRow =
        jdbcClient.sql(
            """
            INSERT INTO stock_watchlist (ticker, exchange, name, memo, is_active, "group")
            VALUES (:ticker, :exchange, :name, :memo, TRUE, :group)
            RETURNING id, ticker, exchange, name, memo, is_active, "group", added_at
            """,
        )
            .param("ticker", ticker)
            .param("exchange", exchange)
            .param("name", name)
            .param("memo", memo)
            .param("group", group)
            .query(rowMapper)
            .single()

    fun findById(id: Int): WatchlistRow? = jdbcClient.sql(
        """
        SELECT id, ticker, exchange, name, memo, is_active, "group", added_at
        FROM stock_watchlist WHERE id = :id
        """,
    )
        .param("id", id)
        .query(rowMapper)
        .list()
        .firstOrNull()

    /** null 필드는 유지. 변경할 필드가 없으면 false */
    fun update(id: Int, item: WatchlistUpdateIn): Boolean {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        addUpdateField(sets, params, "ticker", item.ticker)
        addUpdateField(sets, params, "exchange", item.exchange)
        addUpdateField(sets, params, "name", item.name)
        addUpdateField(sets, params, "memo", item.memo)
        addUpdateField(sets, params, "is_active", item.isActive)
        addUpdateField(sets, params, "\"group\"", item.group)
        if (sets.isEmpty()) {
            return false
        }
        jdbcClient.sql("UPDATE stock_watchlist SET ${sets.joinToString(", ")} WHERE id = :id")
            .param("id", id)
            .params(params)
            .update()
        return true
    }

    private fun addUpdateField(
        sets: MutableList<String>,
        params: MutableMap<String, Any>,
        column: String,
        value: Any?,
    ) {
        if (value != null) {
            val key = column.replace("\"", "")
            sets += "$column = :$key"
            params[key] = value
        }
    }
}
