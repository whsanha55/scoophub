package com.scoophub.stock.vo

import java.time.Instant

/** stock_watchlist 조회 프로젝션 */
data class WatchlistRow(
    val id: Int,
    val ticker: String,
    val exchange: String,
    val name: String,
    val memo: String?,
    val isActive: Boolean,
    val group: String?,
    val addedAt: Instant,
)
