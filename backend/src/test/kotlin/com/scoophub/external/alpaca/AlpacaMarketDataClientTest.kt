package com.scoophub.external.alpaca

import com.scoophub.global.config.ScoophubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParamCount
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RestClientTest(AlpacaMarketDataClient::class)
@EnableConfigurationProperties(ScoophubProperties::class)
@TestPropertySource(properties = ["scoophub.alpaca.api-key=key", "scoophub.alpaca.api-secret=secret"])
class AlpacaMarketDataClientTest @Autowired constructor(
    private val client: AlpacaMarketDataClient,
    private val server: MockRestServiceServer,
) {
    @TestConfiguration
    class FixedClock {
        // 2026-10-07 10:00 ET
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-07T14:00:00Z"), ZoneOffset.UTC)
    }

    @Test
    fun `일봉은 최근 2년을 SIP split 조정으로 요청하고 다음 페이지까지 모아 ET 날짜로 변환한다`() {
        // given
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("${AlpacaMarketDataClient.BASE_URL}/v2/stocks/bars")))
            .andExpect(header("APCA-API-KEY-ID", "key"))
            .andExpect(header("APCA-API-SECRET-KEY", "secret"))
            .andExpect(queryParam("symbols", "AAPL,QQQ"))
            .andExpect(queryParam("feed", "sip"))
            .andExpect(queryParam("adjustment", "split"))
            .andExpect(queryParam("start", "2024-10-07"))
            .andExpect(queryParam("end", "2026-10-07T13:44:00Z"))
            .andExpect(queryParamCount(7)) // page_token 없음
            .andRespond(
                withSuccess(
                    """{"bars":{"AAPL":[{"t":"2026-10-05T04:00:00Z","o":1,"h":2,"l":0.5,"c":1.5,"v":100}]},
                       "next_page_token":"p2"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("${AlpacaMarketDataClient.BASE_URL}/v2/stocks/bars")))
            .andExpect(queryParam("page_token", "p2"))
            .andRespond(
                withSuccess(
                    """{"bars":{"AAPL":[{"t":"2026-10-06T04:00:00Z","o":1.5,"h":3,"l":1,"c":2.5,"v":200}],
                       "QQQ":[{"t":"2026-10-06T04:00:00Z","o":750,"h":760,"l":745,"c":759.66,"v":3000}]},
                       "next_page_token":null}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when
        val bars = client.dailyBars(listOf("AAPL", "QQQ"))

        // then
        assertThat(bars["AAPL"]!!.map { it.date })
            .containsExactly(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-06"))
        assertThat(bars["QQQ"]!!.single().close).isEqualTo(759.66)
        assertThat(bars["QQQ"]!!.single().interval).isEqualTo("1D")
        server.verify()
    }

    @Test
    fun `스냅샷은 최근 체결가와 전일 종가로 등락을 계산하고 응답에 없는 심볼은 뺀다`() {
        // given
        server.expect(requestTo("${AlpacaMarketDataClient.BASE_URL}/v2/stocks/snapshots?symbols=QQQ,NONE"))
            .andRespond(
                withSuccess(
                    """{"QQQ":{"latestTrade":{"p":760.0},
                       "dailyBar":{"o":755,"h":761,"l":754,"c":759,"v":5000},
                       "prevDailyBar":{"c":750.0}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when
        val quotes = client.snapshots(listOf("QQQ", "NONE"))

        // then
        val qqq = quotes.getValue("QQQ")
        assertThat(qqq.price).isEqualTo(760.0)
        assertThat(qqq.change).isEqualTo(10.0)
        assertThat(qqq.changePercent).isCloseTo(1.3333, org.assertj.core.data.Offset.offset(0.0001))
        assertThat(qqq.high).isEqualTo(761.0)
        assertThat(quotes).doesNotContainKey("NONE")
    }

    @Test
    fun `옵션 체인은 60일 안의 만기만 요청해 OCC 심볼을 만기별 콜 풋으로 묶고 최신 거래일이 아닌 거래량은 0 으로 본다`() {
        // given
        server.expect(
            requestTo(
                org.hamcrest.Matchers.startsWith("${AlpacaMarketDataClient.BASE_URL}/v1beta1/options/snapshots/QQQ"),
            ),
        )
            .andExpect(queryParam("expiration_date_gte", "2026-10-07"))
            .andExpect(queryParam("expiration_date_lte", "2026-12-06"))
            .andRespond(
                withSuccess(
                    """{"snapshots":{
                         "QQQ261016C00760000":{"latestQuote":{"bp":8.4,"ap":8.52},"latestTrade":{"p":8.5},
                                               "dailyBar":{"t":"2026-10-06T04:00:00Z","v":10241}},
                         "QQQ261009P00760000":{"latestQuote":{"bp":4.1,"ap":4.31},"latestTrade":{"p":4.2},
                                               "dailyBar":{"t":"2026-10-06T04:00:00Z","v":24456}},
                         "QQQ261009C00760000":{"latestQuote":{"bp":4.25,"ap":4.39},"latestTrade":{"p":4.3},
                                               "dailyBar":{"t":"2026-10-06T04:00:00Z","v":9675}},
                         "QQQ261009C00900500":{"latestQuote":{"bp":0,"ap":0.01},
                                               "dailyBar":{"t":"2026-09-20T04:00:00Z","v":7}}
                       },"next_page_token":null}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when
        val chains = client.optionChains("QQQ")

        // then
        assertThat(chains.map { it.expiry })
            .containsExactly(LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-16"))
        val near = chains.first()
        assertThat(near.calls.map { it.strike }).containsExactlyInAnyOrder(760.0, 900.5)
        assertThat(near.calls.first { it.strike == 760.0 }.volume).isEqualTo(9675)
        assertThat(near.calls.first { it.strike == 900.5 }.volume).isZero()
        assertThat(near.puts.single().bid).isEqualTo(4.1)
        assertThat(chains[1].puts).isEmpty()
    }

    @Test
    fun `429 응답은 상태 코드를 담은 예외로 바꾼다`() {
        // given
        server.expect(
            requestTo(org.hamcrest.Matchers.startsWith("${AlpacaMarketDataClient.BASE_URL}/v2/stocks/snapshots")),
        )
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("""{"message":"too many requests"}"""))

        // when
        val exception = assertThrows<AlpacaMarketDataException> { client.snapshots(listOf("QQQ")) }

        // then
        assertThat(exception.statusCode).isEqualTo(429)
    }

    @Test
    fun `심볼이 없으면 호출하지 않는다`() {
        // when & then
        assertThat(client.dailyBars(emptyList())).isEmpty()
        assertThat(client.snapshots(emptyList())).isEmpty()
        server.verify()
    }
}
