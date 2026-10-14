package com.scoophub.stock.service

import com.scoophub.external.alpaca.AlpacaMarketDataClient
import com.scoophub.external.alpaca.AlpacaMarketDataException
import com.scoophub.stock.StockGex
import com.scoophub.stock.dto.GexOut
import com.scoophub.stock.repository.StockGexQueryRepository
import com.scoophub.stock.vo.FetchOutcome
import com.scoophub.stock.vo.StockGexRow
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

private val log = KotlinLogging.logger {}

/** 행사가별 GEX 계산·저장(하루 1회 파이프라인)과 최신 조회 (#264) */
@Service
class StockGexService(
    private val provider: AlpacaMarketDataClient,
    private val gexRepository: StockGexQueryRepository,
    private val clock: Clock,
    private val pacing: Duration = DEFAULT_PACING,
) {
    /** saved 는 저장한 티커 수 */
    fun computeGex(tickers: List<String>): FetchOutcome {
        val snapshotAt = clock.instant()
        val quotes = try {
            provider.snapshots(tickers)
        } catch (e: AlpacaMarketDataException) {
            return FetchOutcome(tickers.size, 0, tickers.associateWith { failureReason(e) })
        }
        val failures = mutableMapOf<String, String>()
        var saved = 0
        tickers.forEachIndexed { index, ticker ->
            if (index > 0) {
                Thread.sleep(pacing)
            }
            try {
                val spot = quotes[ticker]?.price?.takeIf { it > 0.0 }
                if (spot == null) {
                    failures[ticker] = "price unavailable"
                    return@forEachIndexed
                }
                val chain = provider.gexChain(
                    ticker,
                    minStrike = spot * (1 - StockGex.STRIKE_RANGE),
                    maxStrike = spot * (1 + StockGex.STRIKE_RANGE),
                )
                val result = StockGex.compute(chain.contracts, spot)
                // 미결제약정·감마가 있는 계약이 없는 건 데이터가 없는 것이라 실패로 세지 않는다
                if (result == null) {
                    log.info { "GEX skipped for $ticker: no usable options" }
                    return@forEachIndexed
                }
                gexRepository.upsert(
                    StockGexRow(
                        ticker = ticker,
                        snapshotDate = snapshotAt.atZone(ET).toLocalDate(),
                        snapshotAt = snapshotAt,
                        oiDate = chain.oiDate,
                        spot = spot,
                        netGex = result.netGex,
                        gammaFlip = result.gammaFlip,
                        resistances = result.resistances,
                        supports = result.supports,
                        accelerations = result.accelerations,
                        strikes = result.strikes,
                    ),
                )
                saved++
            } catch (e: Exception) {
                log.error(e) { "GEX compute failed for $ticker" }
                failures[ticker] = failureReason(e)
            }
        }
        return FetchOutcome(tickers.size, saved, failures)
    }

    fun findLatest(ticker: String): GexOut? = gexRepository.findLatest(ticker)?.let(GexOut::from)

    private fun failureReason(e: Exception): String =
        (e as? AlpacaMarketDataException)?.statusCode?.let { "HTTP $it" } ?: (e.message ?: e.javaClass.simpleName)

    companion object {
        private val ET: ZoneId = ZoneId.of("America/New_York")

        /** 종목당 2~3콜. 시그마 직후라 Basic 플랜 분당 200콜 한도를 넘지 않게 종목 사이를 띄운다 */
        private val DEFAULT_PACING: Duration = Duration.ofMillis(1500)
    }
}
