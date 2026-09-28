package com.scoophub.global.notify

import com.scoophub.global.crawl.entity.CrawlDataEntity
import com.scoophub.global.crawl.repository.CrawlDataRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** legacy card.py self-check + tests/test_notify.py 카드 파트 포팅 */
class NotifyCardTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val repository = mockk<CrawlDataRepository>()
    private val card = NotifyCard(repository, mockk())

    private fun row(response: String): CrawlDataEntity =
        CrawlDataEntity(1, "c", "p", "k", Instant.EPOCH, jsonMapper.readTree(response), Instant.EPOCH)

    @Test
    fun `escapeHtml 기본`() {
        assertThat(NotifyCard.escapeHtml("a&b<c>d")).isEqualTo("a&amp;b&lt;c&gt;d")
        assertThat(NotifyCard.escapeHtml(null)).isEmpty()
        assertThat(NotifyCard.escapeHtml(23)).isEqualTo("23")
    }

    @Test
    fun `formatCard 헤더와 본문`() {
        val cardText = NotifyCard.formatCard("news", "rss", 5, 12)
        assertThat(cardText).startsWith("📰 [news · rss]")
        assertThat(cardText).contains("신규 5건", "총 12건")

        assertThat(NotifyCard.formatCard("weather", "", 1)).isEqualTo("🌤 [weather]\n신규 1건")
    }

    @Test
    fun `formatCard escape 가 필요한 값도 안전`() {
        val escaped = NotifyCard.formatCard("news<x", "a&b", 1)
        assertThat(escaped).doesNotContain("<x").contains("&amp;")
    }

    @Test
    fun `formatDefault 와 formatNews`() {
        val d = NotifyCard.formatDefault("hacker_news", "top_stories", 3, "\n• <b>x</b>")
        assertThat(d).startsWith("👥 [hacker_news · top_stories]").contains("신규 3건")

        assertThat(NotifyCard.formatDefault("weather", "forecast", 0, "23°C")).doesNotContain("신규")

        val n = NotifyCard.formatNews("rss", 4, "\n• <b>t</b>")
        assertThat(n).startsWith("📰 [news · rss] — 중요도 4+ 4건")
    }

    @Test
    fun `lineFor 도메인별 한 줄 카드`() {
        val hn = NotifyCard.lineFor(
            "hacker_news",
            jsonMapper.readTree("""{"title":"T<x","url":"http://a","score":99}"""),
        )
        assertThat(hn).contains("<b>T&lt;x</b>", "99점", "보기")

        val gh = NotifyCard.lineFor(
            "github_trending",
            jsonMapper.readTree("""{"fullname":"a/b","url":"http://g","stars":10}"""),
        )
        assertThat(gh).contains("<b>a/b</b>", "★10")

        val ph = NotifyCard.lineFor(
            "product_hunt",
            jsonMapper.readTree("""{"name":"PH","ph_url":"http://p","votes_count":7}"""),
        )
        assertThat(ph).contains("<b>PH</b>", "▲7")

        assertThat(
            NotifyCard.lineFor("hacker_news", jsonMapper.readTree("""{"title":"","url":"x"}""")),
        ).isNull()
    }

    @Test
    fun `weekdayKo 한국 요일`() {
        assertThat(NotifyCard.weekdayKo("2026-06-19")).isEqualTo("금")
        assertThat(NotifyCard.weekdayKo("2024-01-01")).isEqualTo("월")
        assertThat(NotifyCard.weekdayKo("2025-12-25")).isEqualTo("목")
        assertThat(NotifyCard.weekdayKo("")).isEmpty()
    }

    @Test
    fun `hasSeat 문자열 정수 혼합 오탐 방지`() {
        assertThat(NotifyCard.hasSeat(jsonMapper.readTree("\"5\""))).isTrue()
        assertThat(NotifyCard.hasSeat(jsonMapper.readTree("3"))).isTrue()
        assertThat(NotifyCard.hasSeat(jsonMapper.readTree("\"0\""))).isFalse()
        assertThat(NotifyCard.hasSeat(jsonMapper.readTree("0"))).isFalse()
        assertThat(NotifyCard.hasSeat(null)).isFalse()
        assertThat(NotifyCard.hasSeat(jsonMapper.readTree("\"\""))).isFalse()
    }

    @Test
    fun `num 대기질 수치 정수 반올림`() {
        assertThat(NotifyCard.num(null)).isNull()
        assertThat(NotifyCard.num(jsonMapper.readTree("15"))).isEqualTo("15")
        assertThat(NotifyCard.num(jsonMapper.readTree("15.4"))).isEqualTo("15")
        assertThat(NotifyCard.num(jsonMapper.readTree("\"abc\""))).isNull()
    }

    @Test
    fun `enrichWeather 스냅샷 카드`() {
        // given — legacy self-check 와 동일 스냅샷
        every { repository.findFirstByCategoryAndPurposeOrderByDateAtDesc("weather", "snapshot") } returns row(
            """
            {"temperature": 23, "feels_like": 21, "humidity": 60, "condition": "맑음",
             "pm10": 15, "pm10_grade": "좋음", "pm25": 18.4, "pm25_grade": "보통", "uv_grade": "보통",
             "weekly_forecast": [
               {"date": "2026-06-21", "mintempC": "18", "maxtempC": "27", "hourly": [{"chanceofrain": "10"}]},
               {"date": "2026-06-22", "mintempC": "17", "maxtempC": "25", "hourly": [{"chanceofrain": "40"}]},
               {"date": "2026-06-23", "mintempC": "16", "maxtempC": "24", "hourly": [{"chanceofrain": "5"}]}
             ]}
            """,
        )

        // when
        val body = card.enrich("weather", "", "base", emptyList())

        // then
        assertThat(body).isNotNull
        assertThat(body).contains("오늘 최저 18°/최고 27°")
        assertThat(body).contains("미세먼지 좋음(15)")
        assertThat(body).contains("초미세먼지 보통(18)") // 18.4 → 18
        assertThat(body).contains("23°C", "습도 60%")
        assertThat(body).contains("예보: 월 17/25 비40% · 화 16/24")
        assertThat(body).doesNotContain("일 18/27") // 오늘은 예보 줄에 없음
    }

    @Test
    fun `enrichKal 2027 Q1 P 잔석 집계`() {
        // given — legacy self-check 와 동일 fixture
        every { repository.findFirst50ByCategoryAndPurposeOrderByUpdatedAtDesc("kal", "bonus_seat") } returns listOf(
            row(
                """
                {"departureAirport": "ICN", "arrivalAirport": "LHR", "flightList": [
                  {"departureDate": "20270115", "flightDetailList": [
                    {"frontBookingClass": "P", "availableSeat": "2"},
                    {"frontBookingClass": "P", "availableSeat": "0"},
                    {"frontBookingClass": "C", "availableSeat": "9"}
                  ]},
                  {"departureDate": "20270220", "flightDetailList": [
                    {"frontBookingClass": "P", "availableSeat": 1}
                  ]},
                  {"departureDate": "20260615", "flightDetailList": [
                    {"frontBookingClass": "P", "availableSeat": "9"}
                  ]}
                ]}
                """,
            ),
            row(
                """
                {"arrivalAirport": "ZZZ", "flightList": [
                  {"departureDate": "20270310", "flightDetailList": [
                    {"frontBookingClass": "P", "availableSeat": "1"}
                  ]}
                ]}
                """,
            ),
        )

        // when
        val body = card.enrich("kal_bonus", "", "base", emptyList())

        // then
        assertThat(body).isNotNull
        // LHR: 202701(1건, "2">0) + 202702(1건) = 2건
        assertThat(body).contains("• 런던/히스로(LHR): P 잔석 2건")
        assertThat(body).contains("• ZZZ: P 잔석 1건") // ROUTES 미포함 → arr코드만
    }

    @Test
    fun `enrichBatch sort key 탑5 정렬`() {
        // given — row 하나 = 아이템 하나
        val items = (1..7).map { i -> row("{\"title\":\"t$i\",\"url\":\"u$i\",\"score\":$i}") }
        every {
            repository.findFirst50ByCategoryAndPurposeOrderByUpdatedAtDesc("community", "hackernews")
        } returns items

        // when
        val body = card.enrich("hacker_news", "", "base", emptyList())

        // then — score 내림차순 탑5
        assertThat(body).isNotNull
        assertThat(body).contains("7점", "6점", "5점")
        assertThat(body).doesNotContain("1점", "2점")
        assertThat(body).startsWith("👥 [hacker_news]\n신규 5건")
    }

    @Test
    fun `미정의 카테고리는 base 카드 그대로`() {
        assertThat(card.enrich("unknown", "", "base-text", emptyList())).isEqualTo("base-text")
    }
}
