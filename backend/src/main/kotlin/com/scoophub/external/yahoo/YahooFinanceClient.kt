package com.scoophub.external.yahoo

import com.scoophub.global.jackson.elements
import com.scoophub.global.jackson.scalar
import com.scoophub.stock.vo.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate

private val log = KotlinLogging.logger {}

data class Quote(
    val regularMarketPrice: Double,
    val regularMarketChange: Double,
    val regularMarketChangePercent: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
)

data class OptionQuote(
    val contractSymbol: String,
    val strike: Double,
    val impliedVolatility: Double,
    val volume: Long,
    val openInterest: Long,
    val bid: Double,
    val ask: Double,
    val lastPrice: Double,
)

data class OptionsChain(
    val expiries: List<String>,
    val expiry: String,
    val calls: List<OptionQuote>,
    val puts: List<OptionQuote>,
)

/**
 * legacy `yfinance` 대체 — Yahoo Finance HTTP API 직접 호출 (3초 throttle).
 * chart/quote 는 v8 chart meta(공개). options 는 cookie+crumb 획득 후 v7 호출.
 */
@Component
class YahooFinanceClient(
    restClientBuilder: RestClient.Builder,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    private val restClient = restClientBuilder.build()

    @Volatile
    private var lastCallMonotonic: Long = 0L

    @Volatile
    private var crumb: String? = null

    @Volatile
    private var cookie: String? = null

    private fun throttle() {
        val rateLimitMs = 3_000L
        synchronized(this) {
            val elapsed = System.nanoTime() / 1_000_000 - lastCallMonotonic
            val wait = rateLimitMs - elapsed
            if (wait > 0) {
                Thread.sleep(wait)
            }
            lastCallMonotonic = System.nanoTime() / 1_000_000
        }
    }

    /** OHLCV 캔들 (period=6mo 고정 — legacy _get_period(130) 와 동일) */
    fun chart(ticker: String, interval: String = "1d"): List<Candle> {
        log.info { "YahooFinanceClient.chart() 진입 — ticker=$ticker, interval=$interval" }
        throttle()
        return try {
            val body = restClient.get()
                .uri(
                    "https://query1.finance.yahoo.com/v8/finance/chart/{ticker}?interval={interval}&period=6mo",
                    ticker,
                    interval,
                )
                .headers { it.set("User-Agent", USER_AGENT) }
                .retrieve()
                .body(JsonNode::class.java)
                ?: return emptyList()
            val result = body["result"].elements().firstOrNull() ?: return emptyList()
            val timestamps = result["timestamp"].elements()
            val quote = result["indicators"]["quote"].elements().firstOrNull() ?: return emptyList()
            val opens = quote["open"].elements()
            val highs = quote["high"].elements()
            val lows = quote["low"].elements()
            val closes = quote["close"].elements()
            val volumes = quote["volume"].elements()
            val canonical = CANONICAL_INTERVAL[interval] ?: interval.uppercase()

            timestamps.mapIndexedNotNull { i, ts ->
                val o = opens.getOrNull(i)?.takeIf { !it.isNull } ?: return@mapIndexedNotNull null
                val h = highs.getOrNull(i)?.takeIf { !it.isNull } ?: return@mapIndexedNotNull null
                val l = lows.getOrNull(i)?.takeIf { !it.isNull } ?: return@mapIndexedNotNull null
                val c = closes.getOrNull(i)?.takeIf { !it.isNull } ?: return@mapIndexedNotNull null
                Candle(
                    ticker = ticker,
                    interval = canonical,
                    date = LocalDate.ofEpochDay(ts.asLong() / 86_400), // UTC epoch → 날짜
                    open = o.asDouble(),
                    high = h.asDouble(),
                    low = l.asDouble(),
                    close = c.asDouble(),
                    volume = volumes.getOrNull(i)?.takeIf { !it.isNull }?.asDouble() ?: 0.0,
                )
            }
        } catch (e: Exception) {
            log.warn { "yahoo chart($ticker) failed: ${e.message}" }
            emptyList()
        }
    }

    /** 현재가/등락 — chart meta (v7 quote 는 crumb 필수라 미사용) */
    fun quote(ticker: String): Quote? {
        log.info { "YahooFinanceClient.quote() 진입 — ticker=$ticker" }
        throttle()
        return try {
            val body = restClient.get()
                .uri("https://query1.finance.yahoo.com/v8/finance/chart/{ticker}?interval=1d&period=5d", ticker)
                .headers { it.set("User-Agent", USER_AGENT) }
                .retrieve()
                .body(JsonNode::class.java)
                ?: return null
            val meta = body["result"].elements().firstOrNull()?.get("meta") ?: return null
            Quote(
                regularMarketPrice = meta.scalar("regularMarketPrice")?.toDouble() ?: 0.0,
                regularMarketChange = meta.scalar("regularMarketChange")?.toDouble()
                    ?: (
                        meta.scalar("chartPreviousClose")?.toDouble()?.let { pc ->
                            (meta.scalar("regularMarketPrice")?.toDouble() ?: 0.0) - pc
                        } ?: 0.0
                        ),
                regularMarketChangePercent = meta.scalar("regularMarketChangePercent")?.toDouble() ?: 0.0,
                open = meta.scalar("regularMarketPrice")?.toDouble() ?: 0.0, // meta open 은 정규장 이전 갱신 — 가격으로 대체
                high = meta.scalar("regularMarketDayHigh")?.toDouble() ?: 0.0,
                low = meta.scalar("regularMarketDayLow")?.toDouble() ?: 0.0,
                volume = meta.scalar("regularMarketVolume")?.toDouble() ?: 0.0,
            )
        } catch (e: Exception) {
            log.warn { "yahoo quote($ticker) failed: ${e.message}" }
            null
        }
    }

    /** 옵션 체인. cookie+crumb 필요 — 실패 시 재획득 후 1회 재시도. 만기 없으면 null */
    fun optionsChain(ticker: String, expiry: String? = null): OptionsChain? {
        log.info { "YahooFinanceClient.optionsChain() 진입 — ticker=$ticker, expiry=$expiry" }
        throttle()
        return try {
            fetchOptions(ticker, expiry) ?: run {
                refreshCrumb()
                fetchOptions(ticker, expiry)
            }
        } catch (e: Exception) {
            log.warn { "yahoo options_chain($ticker) failed: ${e.message}" }
            null
        }
    }

    private fun fetchOptions(ticker: String, expiry: String?): OptionsChain? {
        val crumbValue = crumb ?: return null
        val uri = "https://query2.finance.yahoo.com/v7/finance/options/{ticker}?crumb={crumb}" +
            (expiry?.let { "&expiry=$it" } ?: "")
        val body = restClient.get()
            .uri(uri, ticker, crumbValue)
            .headers {
                it.set("User-Agent", USER_AGENT)
                cookie?.let { c -> it.set("Cookie", c) }
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?: return null
        val expiries = body["optionChain"]["result"].elements().firstOrNull()
            ?.get("expirationDates")?.elements()?.map { epoch ->
                java.time.Instant.ofEpochSecond(epoch.asLong()).atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate().toString()
            }.orEmpty()
        if (expiries.isEmpty()) {
            return null
        }
        val chosen = expiry ?: expiries.first()
        val node = body["optionChain"]["result"].elements().firstOrNull()?.get("options")?.elements()?.firstOrNull()
            ?: return null

        fun parse(node: JsonNode): List<OptionQuote> = node.elements().map { o ->
            OptionQuote(
                contractSymbol = o.scalar("contractSymbol") ?: "",
                strike = o["strike"].asDouble(0.0),
                impliedVolatility = o["impliedVolatility"].asDouble(0.0),
                volume = o["volume"]?.takeIf { !it.isNull }?.asLong() ?: 0L,
                openInterest = o["openInterest"]?.takeIf { !it.isNull }?.asLong() ?: 0L,
                bid = o["bid"].asDouble(0.0),
                ask = o["ask"].asDouble(0.0),
                lastPrice = o["lastPrice"].asDouble(0.0),
            )
        }
        return OptionsChain(
            expiries = expiries,
            expiry = chosen,
            calls = parse(node["calls"]),
            puts = parse(node["puts"]),
        )
    }

    /** fc.yahoo.com 에서 cookie, getcrumb 에서 crumb 획득 */
    private fun refreshCrumb() {
        try {
            val cookieResponse = restClient.get()
                .uri("https://fc.yahoo.com")
                .headers { it.set("User-Agent", USER_AGENT) }
                .exchange { _, response ->
                    response.headers.get("Set-Cookie")?.firstOrNull()
                }
            cookie = cookieResponse?.substringBefore(";")
            val crumbValue = restClient.get()
                .uri("https://query2.finance.yahoo.com/v1/test/getcrumb")
                .headers {
                    it.set("User-Agent", USER_AGENT)
                    cookie?.let { c -> it.set("Cookie", c) }
                }
                .retrieve()
                .body(String::class.java)
                ?.trim()
            if (!crumbValue.isNullOrEmpty()) {
                crumb = crumbValue
                log.info { "yahoo crumb 획득 완료" }
            }
        } catch (e: Exception) {
            log.warn { "yahoo crumb 획득 실패: ${e.message}" }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

        private val CANONICAL_INTERVAL = mapOf("1d" to "1D", "1wk" to "1W", "1mo" to "1M", "1h" to "1H")
    }
}
