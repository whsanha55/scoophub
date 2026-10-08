package com.scoophub.stock.service

import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.external.alpaca.AlpacaMarketDataException
import com.scoophub.stock.StockSigma
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.vo.FetchOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock

private val log = KotlinLogging.logger {}

/** 시세 수집 — 시그마 계산, 캔들 동기화. 스케줄 잡과 수동 트리거가 함께 쓴다 */
@Service
class StockCrawlService(
    private val provider: AlpacaMarketDataClient,
    private val watchlistRepository: StockWatchlistRepository,
    private val sigmaRepository: StockSigmaRepository,
    private val candleRepository: StockCandleRepository,
    private val clock: Clock,
) {
    /** ATM straddle 기반 주간만기 시그마 계산 후 저장. saved 는 저장한 티커 수 */
    fun computeSigma(targetTickers: List<String>): FetchOutcome {
        val snapshotAt = clock.instant()
        val failures = mutableMapOf<String, String>()
        val quotes = try {
            provider.snapshots(targetTickers)
        } catch (e: AlpacaMarketDataException) {
            return FetchOutcome(targetTickers.size, 0, targetTickers.associateWith { failureReason(e) })
        }
        var saved = 0
        for (ticker in targetTickers) {
            try {
                val price = quotes[ticker]?.price ?: 0.0
                if (price <= 0.0) {
                    failures[ticker] = "price unavailable"
                    continue
                }
                val result = StockSigma.computeSigmaFromOptions(
                    provider.optionChains(ticker),
                    ticker,
                    price,
                    snapshotAt,
                )
                if (result == null) {
                    failures[ticker] = "no sigma computed"
                    continue
                }
                sigmaRepository.upsert(
                    ticker = result.ticker,
                    expiryDate = result.expiryDate,
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
            } catch (e: Exception) {
                log.error(e) { "Sigma compute failed for $ticker" }
                failures[ticker] = failureReason(e)
            }
        }
        return FetchOutcome(targetTickers.size, saved, failures)
    }

    /** 활성 관심종목 일봉 동기화. saved 는 저장한 캔들 수 */
    fun syncCandles(): FetchOutcome {
        val tickers = watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }
        val barsByTicker = try {
            provider.dailyBars(tickers)
        } catch (e: AlpacaMarketDataException) {
            return FetchOutcome(tickers.size, 0, tickers.associateWith { failureReason(e) })
        }
        val failures = mutableMapOf<String, String>()
        var totalSaved = 0
        for (ticker in tickers) {
            val candles = barsByTicker[ticker].orEmpty()
            if (candles.isEmpty()) {
                failures[ticker] = "no candles"
                continue
            }
            try {
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
            } catch (e: Exception) {
                log.error(e) { "Candle save failed for $ticker" }
                failures[ticker] = failureReason(e)
            }
        }
        log.info { "Candle sync: $totalSaved candles for ${tickers.size} tickers, ${failures.size} failed" }
        return FetchOutcome(tickers.size, totalSaved, failures)
    }

    private fun failureReason(e: Exception): String =
        (e as? AlpacaMarketDataException)?.statusCode?.let { "HTTP $it" } ?: (e.message ?: e.javaClass.simpleName)
}
