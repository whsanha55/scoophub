package com.scoophub.stock.vo

import java.time.LocalDate

/** legacy `stock/models.py` Candle — OHLCV 값 객체 (엔티티와 분리) */
data class Candle(
    val ticker: String,
    val interval: String,
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)
