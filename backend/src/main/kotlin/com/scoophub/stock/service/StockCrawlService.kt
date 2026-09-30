package com.scoophub.stock.service

import com.scoophub.external.yahoo.YahooFinanceClient
import com.scoophub.stock.StockSigma
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock

private val log = KotlinLogging.logger {}

/** 수동 크롤 트리거 — 시그마 즉시 계산, 캔들 동기화 */
@Service
class StockCrawlService(
    private val provider: YahooFinanceClient,
    private val watchlistRepository: StockWatchlistRepository,
    private val sigmaRepository: StockSigmaRepository,
    private val candleRepository: StockCandleRepository,
    private val clock: Clock,
) {
    /** ATM straddle 기반 시그마 계산 후 저장. (saved, errors) */
    fun computeSigma(targetTickers: List<String>): Pair<Int, Int> {
        val snapshotAt = clock.instant()
        var saved = 0
        var errors = 0
        for (ticker in targetTickers) {
            try {
                val price = provider.quote(ticker)?.regularMarketPrice ?: 0.0
                if (price <= 0.0) {
                    errors++
                    continue
                }
                for (result in StockSigma.computeSigmaFromOptions(provider, ticker, price, snapshotAt, clock)) {
                    sigmaRepository.upsert(
                        ticker = result.ticker,
                        expiryDate = result.expiryDate ?: continue,
                        snapshotDate = result.snapshotDate,
                        snapshotAt = result.snapshotAt,
                        currentPrice = result.currentPrice,
                        atmStrike = result.atmStrike,
                        atmCall = result.atmCall,
                        atmPut = result.atmPut,
                        expectedMove = result.expectedMove,
                        expectedMovePct = result.expectedMovePct,
                        totalCallVolume = result.totalCallVolume,
                        totalPutVolume = result.totalPutVolume,
                        putCallVolumeRatio = result.putCallVolumeRatio,
                        atmCallVolume = result.atmCallVolume,
                        atmPutVolume = result.atmPutVolume,
                    )
                    saved++
                }
            } catch (e: Exception) {
                log.error(e) { "Sigma compute failed for $ticker" }
                errors++
            }
        }
        return saved to errors
    }

    /** 활성 관심종목 일봉 동기화. 저장한 캔들 수 */
    fun syncCandles(): Int {
        val items = watchlistRepository.findByIsActiveOrderByAddedAt()
        var totalSaved = 0
        for (item in items) {
            try {
                val candles = provider.chart(item.ticker, "1d")
                if (candles.isNotEmpty()) {
                    candleRepository.saveBatch(
                        tickers = candles.map { it.ticker }.toTypedArray(),
                        intervals = candles.map { it.interval }.toTypedArray(),
                        dates = candles.map { it.date }.toTypedArray(),
                        opens = candles.map { it.open }.toDoubleArray(),
                        highs = candles.map { it.high }.toDoubleArray(),
                        lows = candles.map { it.low }.toDoubleArray(),
                        closes = candles.map { it.close }.toDoubleArray(),
                        volumes = candles.map { it.volume }.toDoubleArray(),
                    )
                    totalSaved += candles.size
                }
            } catch (e: Exception) {
                log.warn { "Candle sync failed for ${item.ticker}: ${e.message}" }
            }
        }
        return totalSaved
    }
}
