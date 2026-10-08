package com.scoophub.external.alpaca

import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.jackson.elements
import com.scoophub.global.jackson.scalar
import com.scoophub.stock.vo.Candle
import com.scoophub.stock.vo.OptionQuote
import com.scoophub.stock.vo.OptionsChain
import com.scoophub.stock.vo.Quote
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriBuilder
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/** 시세 조회 실패. statusCode 는 HTTP 응답이 없으면 null */
class AlpacaMarketDataException(val statusCode: Int?, message: String) : RuntimeException(message)

/** Alpaca Market Data REST — 일봉, 스냅샷, 옵션 체인 스냅샷 */
@Component
class AlpacaMarketDataClient(
    props: ScoophubProperties,
    restClientBuilder: RestClient.Builder,
    private val clock: Clock,
) {
    private val restClient = restClientBuilder
        .baseUrl(BASE_URL)
        .defaultHeader("APCA-API-KEY-ID", props.alpaca.apiKey)
        .defaultHeader("APCA-API-SECRET-KEY", props.alpaca.apiSecret)
        .defaultStatusHandler(HttpStatusCode::isError) { request, response ->
            val status = response.statusCode.value()
            val body = response.body.readAllBytes().decodeToString().take(200)
            log.warn { "alpaca ${request.uri.path} HTTP $status: $body" }
            throw AlpacaMarketDataException(status, "HTTP $status $body")
        }
        .build()

    /** 최근 10년 split 조정 일봉. 응답에 없는 심볼은 결과에서 빠진다 */
    fun dailyBars(symbols: List<String>): Map<String, List<Candle>> {
        if (symbols.isEmpty()) {
            return emptyMap()
        }
        val now = clock.instant()
        // 백테스트(/stock/backtest)가 2018·2020·2022 하락장을 포함하도록 10년 (#251)
        val start = now.atZone(ET).toLocalDate().minusYears(HISTORY_YEARS)
        // 무료 플랜은 최근 15분 SIP 조회가 막힌다. 기본 IEX feed 는 거래량이 통합 거래량의 일부라 SIP 를 쓴다.
        val end = now.minus(SIP_DELAY).truncatedTo(ChronoUnit.SECONDS)
        val result = mutableMapOf<String, MutableList<Candle>>()
        paginate(emptyArray()) { builder ->
            builder.path("/v2/stocks/bars")
                .queryParam("symbols", symbols.joinToString(","))
                .queryParam("timeframe", "1Day")
                .queryParam("start", start)
                .queryParam("end", end)
                .queryParam("adjustment", "split")
                .queryParam("feed", "sip")
                .queryParam("limit", PAGE_LIMIT_BARS)
        }.forEach { page ->
            page["bars"]?.properties()?.forEach { (symbol, bars) ->
                result.getOrPut(symbol) { mutableListOf() } += bars.elements().map { toCandle(symbol, it) }
            }
        }
        return result
    }

    /** 현재가(latestTrade)와 당일·전일 일봉 기반 등락. 응답에 없는 심볼은 결과에서 빠진다 */
    fun snapshots(symbols: List<String>): Map<String, Quote> {
        if (symbols.isEmpty()) {
            return emptyMap()
        }
        val body = get { it.path("/v2/stocks/snapshots").queryParam("symbols", symbols.joinToString(",")).build() }
        return symbols.mapNotNull { symbol -> body[symbol]?.let(::toQuote)?.let { symbol to it } }.toMap()
    }

    /** 오늘(ET)부터 14일 안에 만기인 옵션 체인. 만기 오름차순 */
    fun optionChains(underlying: String): List<OptionsChain> {
        val today = clock.instant().atZone(ET).toLocalDate()
        // 시그마는 주간만기 하나만 쓴다(금요일 스냅샷이면 다음 주 금요일까지). 장기 만기까지 받으면 SPY 는 13페이지가 된다
        val contracts = paginate(arrayOf(underlying)) { builder ->
            builder.path("/v1beta1/options/snapshots/{underlying}")
                .queryParam("expiration_date_gte", today)
                .queryParam("expiration_date_lte", today.plusDays(OPTION_EXPIRY_WINDOW_DAYS))
                .queryParam("limit", PAGE_LIMIT_OPTIONS)
        }.flatMap { page ->
            page["snapshots"]?.properties()?.mapNotNull { (symbol, snapshot) -> toContract(symbol, snapshot) }.orEmpty()
        }
        // 한동안 거래가 없던 계약은 dailyBar 가 과거 날짜라, 그 거래량이 합계에 섞이지 않게 0 으로 본다
        val latestBarDate = contracts.mapNotNull { it.barDate }.maxOrNull()
        return contracts
            .groupBy { it.expiry }
            .toSortedMap()
            .map { (expiry, list) ->
                fun quotes(isCall: Boolean) = list.filter { it.isCall == isCall }.map {
                    it.quote.copy(volume = if (it.barDate == latestBarDate) it.quote.volume else 0L)
                }
                OptionsChain(expiry = expiry, calls = quotes(true), puts = quotes(false))
            }
    }

    private fun paginate(uriVariables: Array<Any>, request: (UriBuilder) -> UriBuilder): List<JsonNode> {
        val pages = mutableListOf<JsonNode>()
        var pageToken: String? = null
        do {
            val token = pageToken
            val page = get { builder ->
                request(builder).apply { token?.let { queryParam("page_token", it) } }.build(*uriVariables)
            }
            pages.add(page)
            pageToken = page.scalar("next_page_token")
        } while (pageToken != null)
        return pages
    }

    private fun get(uri: (UriBuilder) -> URI): JsonNode = try {
        restClient.get().uri(uri).retrieve().body(JsonNode::class.java)
            ?: throw AlpacaMarketDataException(null, "empty response")
    } catch (e: RestClientException) {
        log.warn { "alpaca request failed: ${e.message}" }
        throw AlpacaMarketDataException(null, e.message ?: e.javaClass.simpleName)
    }

    private fun toCandle(symbol: String, bar: JsonNode) = Candle(
        ticker = symbol,
        interval = "1D",
        date = Instant.parse(bar["t"].asText()).atZone(ET).toLocalDate(),
        open = bar["o"].asDouble(),
        high = bar["h"].asDouble(),
        low = bar["l"].asDouble(),
        close = bar["c"].asDouble(),
        volume = bar["v"].asDouble(),
    )

    private fun toQuote(snapshot: JsonNode): Quote? {
        val daily = snapshot["dailyBar"]
        val price = snapshot["latestTrade"]?.get("p")?.asDouble()?.takeIf { it > 0 }
            ?: daily?.get("c")?.asDouble()?.takeIf { it > 0 }
            ?: return null
        val prevClose = snapshot["prevDailyBar"]?.get("c")?.asDouble() ?: 0.0
        val change = if (prevClose > 0) price - prevClose else 0.0
        return Quote(
            price = price,
            change = change,
            changePercent = if (prevClose > 0) change / prevClose * 100 else 0.0,
            open = daily?.get("o")?.asDouble() ?: 0.0,
            high = daily?.get("h")?.asDouble() ?: 0.0,
            low = daily?.get("l")?.asDouble() ?: 0.0,
            volume = daily?.get("v")?.asDouble() ?: 0.0,
        )
    }

    /** OCC 심볼 `{root}{yyMMdd}{C|P}{strike×1000 8자리}` 파싱 */
    private fun toContract(symbol: String, snapshot: JsonNode): OptionContract? {
        if (symbol.length < OCC_SUFFIX_LENGTH + 1) {
            return null
        }
        val suffix = symbol.takeLast(OCC_SUFFIX_LENGTH)
        val quote = snapshot["latestQuote"]
        val daily = snapshot["dailyBar"]
        return OptionContract(
            expiry = LocalDate.parse(suffix.take(6), OCC_DATE),
            isCall = suffix[6] == 'C',
            barDate = daily?.get("t")?.let { Instant.parse(it.asText()).atZone(ET).toLocalDate() },
            quote = OptionQuote(
                strike = suffix.substring(7).toLong() / 1000.0,
                bid = quote?.get("bp")?.asDouble() ?: 0.0,
                ask = quote?.get("ap")?.asDouble() ?: 0.0,
                lastPrice = snapshot["latestTrade"]?.get("p")?.asDouble() ?: 0.0,
                volume = daily?.get("v")?.asLong() ?: 0L,
            ),
        )
    }

    private class OptionContract(
        val expiry: LocalDate,
        val isCall: Boolean,
        val barDate: LocalDate?,
        val quote: OptionQuote,
    )

    companion object {
        const val BASE_URL = "https://data.alpaca.markets"
        private val ET: ZoneId = ZoneId.of("America/New_York")
        private val SIP_DELAY = Duration.ofMinutes(16)
        private const val HISTORY_YEARS = 10L
        private const val OPTION_EXPIRY_WINDOW_DAYS = 14L
        private const val PAGE_LIMIT_BARS = 10_000
        private const val PAGE_LIMIT_OPTIONS = 1_000
        private const val OCC_SUFFIX_LENGTH = 15
        private val OCC_DATE = DateTimeFormatter.ofPattern("yyMMdd")
    }
}
