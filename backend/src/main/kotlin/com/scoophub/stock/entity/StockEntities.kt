package com.scoophub.stock.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate

/** V4__stock.sql stock_watchlist — 관심종목 */
@Entity
@Table(name = "stock_watchlist")
class StockWatchlistEntity(
    val ticker: String,
    val exchange: String = "NAS",
    val name: String = "",
    val memo: String? = null,
    var isActive: Boolean = true,
    /** V14 — market/sector/individual */
    var group: String = "individual",
    @Column(insertable = false, updatable = false)
    val addedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null
        protected set
}

/** V4 stock_candles — OHLCV */
@Entity
@Table(name = "stock_candles")
class StockCandleEntity(
    val ticker: String,
    val interval: String = "1D",
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null
        protected set
}

/** V4 stock_analysis_results — 기간별 분석 결과 */
@Entity
@Table(name = "stock_analysis_results")
class StockAnalysisResultEntity(
    val ticker: String,
    val exchange: String = "NAS",
    val timeframe: String = "1D",
    val signal: String,
    var totalScore: Double = 0.0,
    var confidence: Double = 0.0,
    var marketRegime: String = "RANGING",
    var price: Double = 0.0,
    var change: Double = 0.0,
    var changeRate: Double = 0.0,
    @JdbcTypeCode(SqlTypes.JSON)
    var technicalScores: JsonNode,
    @JdbcTypeCode(SqlTypes.JSON)
    var technicalDetails: JsonNode,
    var analyzedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null
        protected set
}

/** V4 stock_sigma — ATM 스트래들 스냅샷 */
@Entity
@Table(name = "stock_sigma")
class StockSigmaEntity(
    val ticker: String,
    val expiryDate: LocalDate,
    val snapshotDate: LocalDate,
    var snapshotAt: Instant,
    var currentPrice: Double,
    var atmStrike: Double,
    var atmCall: Double,
    var atmPut: Double,
    var expectedMove: Double,
    var expectedMovePct: Double,
    var totalCallVolume: Long = 0,
    var totalPutVolume: Long = 0,
    var putCallVolumeRatio: Double? = null,
    var atmCallVolume: Long = 0,
    var atmPutVolume: Long = 0,
    @Column(insertable = false, updatable = false)
    val createdAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
