package com.scoophub.stock.repository

import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockCandleEntity
import com.scoophub.stock.entity.StockSigmaEntity
import com.scoophub.stock.entity.StockWatchlistEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

interface StockWatchlistRepository : JpaRepository<StockWatchlistEntity, Int> {

    fun findByIsActiveOrderByAddedAt(isActive: Boolean = true): List<StockWatchlistEntity>

    fun findAllByOrderByAddedAt(): List<StockWatchlistEntity>

    fun findByTickerAndIsActive(ticker: String, isActive: Boolean = true): StockWatchlistEntity?

    fun findByIsActiveAndGroupOrderByAddedAt(isActive: Boolean, group: String): List<StockWatchlistEntity>

    /** 관심종목 제거 — 같은 티커의 다른 행이 없을 때만 관련 캔들/sigma/분석 결과까지 함께 */
    @Modifying
    @Query(
        "DELETE FROM stock_candles WHERE ticker = (SELECT ticker FROM stock_watchlist WHERE id = :id)" +
            " AND NOT EXISTS (SELECT 1 FROM stock_watchlist WHERE ticker = stock_candles.ticker AND id <> :id)",
        nativeQuery = true,
    )
    fun deleteCandlesOf(@Param("id") id: Int)

    @Modifying
    @Query(
        "DELETE FROM stock_sigma WHERE ticker = (SELECT ticker FROM stock_watchlist WHERE id = :id)" +
            " AND NOT EXISTS (SELECT 1 FROM stock_watchlist WHERE ticker = stock_sigma.ticker AND id <> :id)",
        nativeQuery = true,
    )
    fun deleteSigmaOf(@Param("id") id: Int)

    @Modifying
    @Query(
        "DELETE FROM stock_analysis_results WHERE ticker = (SELECT ticker FROM stock_watchlist WHERE id = :id)" +
            " AND NOT EXISTS (SELECT 1 FROM stock_watchlist WHERE ticker = stock_analysis_results.ticker AND id <> :id)",
        nativeQuery = true,
    )
    fun deleteAnalysisOf(@Param("id") id: Int)

    @Modifying
    @Query("DELETE FROM stock_watchlist WHERE id = :id", nativeQuery = true)
    fun deleteByIdRow(@Param("id") id: Int): Int
}

interface StockCandleRepository : JpaRepository<StockCandleEntity, Int> {

    /** 다중 upsert — (ticker, interval, date) 충돌 시 최신 갱신 */
    @Transactional
    @Modifying
    @Query(
        value = """
        INSERT INTO stock_candles (ticker, interval, date, open, high, low, close, volume)
        SELECT * FROM unnest(:tickers, :intervals, :dates, :opens, :highs, :lows, :closes, :volumes)
        ON CONFLICT (ticker, interval, date) DO UPDATE SET
            open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
            close = EXCLUDED.close, volume = EXCLUDED.volume
        """,
        nativeQuery = true,
    )
    fun saveBatch(
        @Param("tickers") tickers: Array<String>,
        @Param("intervals") intervals: Array<String>,
        @Param("dates") dates: Array<LocalDate>,
        @Param("opens") opens: DoubleArray,
        @Param("highs") highs: DoubleArray,
        @Param("lows") lows: DoubleArray,
        @Param("closes") closes: DoubleArray,
        @Param("volumes") volumes: DoubleArray,
    )
}

interface StockAnalysisResultRepository : JpaRepository<StockAnalysisResultEntity, Int> {

    @Transactional
    @Modifying
    @Query(
        value = """
        INSERT INTO stock_analysis_results
            (ticker, exchange, timeframe, signal, total_score, confidence, market_regime,
             price, change, change_rate, technical_scores, technical_details, analyzed_at)
        VALUES (:ticker, :exchange, :timeframe, :signal, :totalScore, :confidence, :marketRegime,
                :price, :change, :changeRate, CAST(:technicalScores AS jsonb), CAST(:technicalDetails AS jsonb), now())
        ON CONFLICT (ticker, timeframe) DO UPDATE SET
            signal = EXCLUDED.signal, total_score = EXCLUDED.total_score,
            confidence = EXCLUDED.confidence, market_regime = EXCLUDED.market_regime,
            price = EXCLUDED.price, change = EXCLUDED.change, change_rate = EXCLUDED.change_rate,
            technical_scores = EXCLUDED.technical_scores, technical_details = EXCLUDED.technical_details,
            analyzed_at = now()
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("ticker") ticker: String,
        @Param("exchange") exchange: String,
        @Param("timeframe") timeframe: String,
        @Param("signal") signal: String,
        @Param("totalScore") totalScore: Double,
        @Param("confidence") confidence: Double,
        @Param("marketRegime") marketRegime: String,
        @Param("price") price: Double,
        @Param("change") change: Double,
        @Param("changeRate") changeRate: Double,
        @Param("technicalScores") technicalScores: String,
        @Param("technicalDetails") technicalDetails: String,
    )

    fun findByTickerInAndTimeframeOrderByTotalScoreDesc(
        tickers: Collection<String>,
        timeframe: String,
    ): List<StockAnalysisResultEntity>

    /** legacy `find_by_tickers` — ORDER BY analyzed_at DESC */
    fun findByTickerInAndTimeframeOrderByAnalyzedAtDesc(
        tickers: Collection<String>,
        timeframe: String,
    ): List<StockAnalysisResultEntity>

    fun findByTimeframeOrderByAnalyzedAtDesc(timeframe: String): List<StockAnalysisResultEntity>
}

interface StockSigmaRepository : JpaRepository<StockSigmaEntity, Long> {

    @Transactional
    @Modifying
    @Query(
        value = """
        INSERT INTO stock_sigma
            (ticker, expiry_date, snapshot_date, snapshot_at, current_price, atm_strike,
             atm_call, atm_put, expected_move, expected_move_pct, total_call_volume, total_put_volume,
             put_call_volume_ratio, atm_call_volume, atm_put_volume)
        VALUES (:ticker, :expiryDate, :snapshotDate, :snapshotAt, :currentPrice, :atmStrike,
                :atmCall, :atmPut, :expectedMove, :expectedMovePct, :totalCallVolume, :totalPutVolume,
                :putCallVolumeRatio, :atmCallVolume, :atmPutVolume)
        ON CONFLICT (ticker, expiry_date, snapshot_date) DO UPDATE SET
            snapshot_at = EXCLUDED.snapshot_at, current_price = EXCLUDED.current_price,
            atm_strike = EXCLUDED.atm_strike, atm_call = EXCLUDED.atm_call, atm_put = EXCLUDED.atm_put,
            expected_move = EXCLUDED.expected_move, expected_move_pct = EXCLUDED.expected_move_pct,
            total_call_volume = EXCLUDED.total_call_volume, total_put_volume = EXCLUDED.total_put_volume,
            put_call_volume_ratio = EXCLUDED.put_call_volume_ratio,
            atm_call_volume = EXCLUDED.atm_call_volume, atm_put_volume = EXCLUDED.atm_put_volume,
            updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("ticker") ticker: String,
        @Param("expiryDate") expiryDate: LocalDate,
        @Param("snapshotDate") snapshotDate: LocalDate,
        @Param("snapshotAt") snapshotAt: Instant,
        @Param("currentPrice") currentPrice: Double,
        @Param("atmStrike") atmStrike: Double,
        @Param("atmCall") atmCall: Double,
        @Param("atmPut") atmPut: Double,
        @Param("expectedMove") expectedMove: Double,
        @Param("expectedMovePct") expectedMovePct: Double,
        @Param("totalCallVolume") totalCallVolume: Long,
        @Param("totalPutVolume") totalPutVolume: Long,
        @Param("putCallVolumeRatio") putCallVolumeRatio: Double?,
        @Param("atmCallVolume") atmCallVolume: Long,
        @Param("atmPutVolume") atmPutVolume: Long,
    )

    fun findFirstByTickerOrderBySnapshotDateDescSnapshotAtDesc(ticker: String): StockSigmaEntity?

    /** legacy `get_latest` — ORDER BY snapshot_date DESC, expiry_date ASC */
    fun findFirstByTickerOrderBySnapshotDateDescExpiryDateAsc(ticker: String): StockSigmaEntity?
}
