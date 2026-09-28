package com.scoophub.stock

import com.scoophub.external.yahoo.OptionQuote
import com.scoophub.external.yahoo.OptionsChain
import com.scoophub.external.yahoo.YahooFinanceClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

private val log = KotlinLogging.logger {}

/** legacy `stock/models.py` SigmaResult (계산 결과 — 엔티티와 분리) */
data class SigmaResult(
    val ticker: String,
    val currentPrice: Double,
    val expiryDate: LocalDate?,
    val atmStrike: Double,
    val atmCall: Double,
    val atmPut: Double,
    val expectedMove: Double,
    val expectedMovePct: Double,
    val snapshotDate: LocalDate,
    val snapshotAt: Instant,
    val source: String,
    val totalCallVolume: Long,
    val totalPutVolume: Long,
    val putCallVolumeRatio: Double?,
    val atmCallVolume: Long,
    val atmPutVolume: Long,
)

/** legacy `stock/sigma.py` — ATM 스트래들 가격으로 sigma(예상움직임) 계산 */
object StockSigma {
    private val ET: ZoneId = ZoneId.of("America/New_York")

    /** 티커당 저장 만기 상한 (yfinance 3초 throttle × 만기수 → 지연 상한) */
    const val MAX_EXPIRIES = 6

    private fun optionPrice(opt: OptionQuote): Double {
        // bid/ask mid 우선, 없으면 lastPrice
        if (opt.bid > 0 && opt.ask > 0) {
            return (opt.bid + opt.ask) / 2
        }
        return opt.lastPrice
    }

    /** ATM 행사가: 현재가와 가장 가까운 공통 행사가 */
    private fun findAtmStrike(calls: List<OptionQuote>, puts: List<OptionQuote>, currentPrice: Double): Double? {
        val common = calls.map { it.strike }.toSet() intersect puts.map { it.strike }.toSet()
        if (common.isEmpty()) {
            return null
        }
        return common.minByOrNull { abs(it - currentPrice) }
    }

    private fun round4(v: Double): Double = (v * 10_000).roundToInt() / 10_000.0

    private fun computeOne(
        chain: OptionsChain,
        ticker: String,
        currentPrice: Double,
        snapshotAt: Instant,
    ): SigmaResult? {
        val atmStrike = findAtmStrike(chain.calls, chain.puts, currentPrice) ?: run {
            log.warn { "No common strikes for $ticker (expiry=${chain.expiry})" }
            return null
        }
        val atmCall = chain.calls.firstOrNull { it.strike == atmStrike } ?: return null
        val atmPut = chain.puts.firstOrNull { it.strike == atmStrike } ?: return null

        val callPrice = optionPrice(atmCall)
        val putPrice = optionPrice(atmPut)
        if (callPrice <= 0 || putPrice <= 0) {
            log.warn { "ATM straddle has zero price for $ticker (call=$callPrice, put=$putPrice)" }
            return null
        }

        val expectedMove = callPrice + putPrice
        val expectedMovePct = expectedMove / currentPrice * 100

        val totalCallVolume = chain.calls.sumOf { it.volume }
        val totalPutVolume = chain.puts.sumOf { it.volume }
        val pcr = if (totalCallVolume > 0) totalPutVolume.toDouble() / totalCallVolume else null

        val expiryDate = runCatching { LocalDate.parse(chain.expiry.take(10)) }.getOrNull()
        return SigmaResult(
            ticker = ticker,
            currentPrice = currentPrice,
            expiryDate = expiryDate,
            atmStrike = atmStrike,
            atmCall = round4(callPrice),
            atmPut = round4(putPrice),
            expectedMove = round4(expectedMove),
            expectedMovePct = round4(expectedMovePct),
            snapshotDate = snapshotAt.atZone(ET).toLocalDate(), // ET 거래일
            snapshotAt = snapshotAt,
            source = "yfinance_straddle",
            totalCallVolume = totalCallVolume,
            totalPutVolume = totalPutVolume,
            putCallVolumeRatio = pcr?.let { round4(it) },
            atmCallVolume = atmCall.volume,
            atmPutVolume = atmPut.volume,
        )
    }

    /** 만기별 sigma 계산. expected_move = ATM call + ATM put */
    fun computeSigmaFromOptions(
        provider: YahooFinanceClient,
        ticker: String,
        currentPrice: Double,
        snapshotAt: Instant? = null,
        clock: Clock,
    ): List<SigmaResult> {
        if (currentPrice <= 0) {
            return emptyList()
        }
        val at = snapshotAt ?: clock.instant()

        val firstChain = provider.optionsChain(ticker) ?: run {
            log.warn { "No options chain data for $ticker" }
            return emptyList()
        }
        val expiries = firstChain.expiries.take(MAX_EXPIRIES)

        val results = expiries.mapNotNull { expiry ->
            val chain = if (expiry == firstChain.expiry) firstChain else provider.optionsChain(ticker, expiry)
            chain?.let { computeOne(it, ticker, currentPrice, at) }
        }
        if (results.isEmpty()) {
            log.warn { "No valid sigma computed for $ticker across ${expiries.size} expiries" }
        }
        return results
    }
}
