package com.scoophub.stock.dto

import com.scoophub.stock.entity.StockWatchlistEntity
import java.sql.ResultSet
import java.time.ZoneOffset

/** legacy `schemas.py` WatchlistItemIn */
data class WatchlistItemIn(
    val ticker: String,
    val exchange: String = "NAS",
    val name: String = "",
    val memo: String? = null,
    val group: String = "individual",
)

/** legacy `schemas.py` WatchlistUpdateIn — 부분 수정 (null 필드는 무시) */
data class WatchlistUpdateIn(
    val ticker: String? = null,
    val exchange: String? = null,
    val name: String? = null,
    val memo: String? = null,
    val isActive: Boolean? = null,
    val group: String? = null,
)

/** legacy `schemas.py` WatchlistItemOut. `added_at` 은 legacy `row_to_watchlist` 처럼 date 만(UTC) 노출 */
data class WatchlistItemOut(
    val id: String,
    val ticker: String,
    val exchange: String,
    val name: String,
    val memo: String? = null,
    val addedAt: String,
    val isActive: Boolean,
    val group: String = "individual",
) {
    companion object {
        fun from(entity: StockWatchlistEntity) = WatchlistItemOut(
            id = entity.id?.toString() ?: "",
            ticker = entity.ticker,
            exchange = entity.exchange,
            name = entity.name,
            memo = entity.memo,
            addedAt = entity.addedAt.atZone(ZoneOffset.UTC).toLocalDate().toString(),
            isActive = entity.isActive,
            group = entity.group,
        )

        fun of(rs: ResultSet) = WatchlistItemOut(
            id = rs.getInt("id").toString(),
            ticker = rs.getString("ticker"),
            exchange = rs.getString("exchange"),
            name = rs.getString("name"),
            memo = rs.getString("memo"),
            addedAt = rs.getTimestamp("added_at").toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString(),
            isActive = rs.getBoolean("is_active"),
            group = rs.getString("group") ?: "individual",
        )
    }
}
