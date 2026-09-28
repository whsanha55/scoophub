package com.scoophub.kal

import com.scoophub.global.jackson.elements
import com.scoophub.global.jackson.scalar
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.ZoneOffset

/** legacy `kal_bonus/config.py` — 대상 노선/기간 상수와 crawl_sources 로더 */
object KalConfig {
    const val ENDPOINT = "https://www.koreanair.com/api/hmp/bonusSeatView/bonusSeatView"
    const val CRAWLER_KEY = "kal_bonus"
    const val CATEGORY = "kal"
    const val PURPOSE = "bonus_seat"
    const val DEPARTURE = "ICN"

    /** 도착 유럽 10노선: (공항코드, 도시명) */
    val ROUTES: List<Pair<String, String>> = listOf(
        "LHR" to "런던/히스로",
        "FCO" to "로마/레오나르도 다빈치",
        "LIS" to "리스본",
        "MAD" to "마드리드",
        "MXP" to "밀라노/말펜사",
        "AMS" to "암스테르담/스키폴",
        "IST" to "이스탄불",
        "ZRH" to "취리히",
        "CDG" to "파리/샤를 드 골",
        "FRA" to "프랑크푸르트",
    )

    /** 오늘(UTC) 기준 13개월 YYYYMM */
    fun targetMonths(clock: Clock, count: Int = 13): List<String> {
        val now = clock.instant().atZone(ZoneOffset.UTC)
        var y = now.year
        var m = now.monthValue
        return (0 until count).map {
            val ym = "%04d%02d".format(y, m)
            m++
            if (m > 12) {
                m = 1
                y++
            }
            ym
        }
    }

    /** crawl_data.key — 호출 단위 = 월×route. 날짜가 prefix 라 월 범위 스캔이 문자열 prefix 로 가능 */
    fun makeKey(departure: String, arrival: String, yearMonth: String): String = "$yearMonth-$departure-$arrival"

    /** YYYYMM → YYYYMMDD(월 첫날). API departureDate 규격 */
    fun monthFirstDay(yearMonth: String): String = "${yearMonth}01"
}

/** crawl_sources(kal_bonus) 활성 row → (departure, routes, months). 없으면 폴백 기본값 */
@Component
class KalRoutesLoader(private val jdbcClient: JdbcClient, private val clock: Clock) {
    data class Targets(val departure: String, val routes: List<Pair<String, String>>, val months: List<String>)

    fun load(): Targets {
        val config: JsonNode? = jdbcClient.sql(
            "SELECT config FROM crawl_sources WHERE crawler = :crawler AND active = true ORDER BY updated_at DESC LIMIT 1",
        )
            .param("crawler", KalConfig.CRAWLER_KEY)
            .query { rs, _ -> rs.getString("config") }
            .optional()
            .orElse(null)
            ?.let { tools.jackson.databind.json.JsonMapper.builder().build().readTree(it) }

        if (config == null || config.isNull) {
            return Targets(KalConfig.DEPARTURE, KalConfig.ROUTES, KalConfig.targetMonths(clock))
        }

        val departure = config.scalar("departure")?.ifEmpty { null } ?: KalConfig.DEPARTURE
        val routes = config["routes"].elements()
            .mapNotNull { r -> r.scalar("arrival")?.let { it to (r.scalar("city") ?: "") } }
            .ifEmpty { KalConfig.ROUTES }
        val months = config["months"].elements().map { it.asText() }
            .ifEmpty { KalConfig.targetMonths(clock) }
        return Targets(departure, routes, months)
    }
}

/** legacy `kal_bonus/cabin.py` — frontBookingClass → cabin_label */
object KalCabin {
    private val LABELS = mapOf(
        "E" to "일반석 보너스",
        "R" to "프리미엄석 보너스",
        "G" to "프리미엄석 좌석승급",
        "P" to "프레스티지석 보너스",
        "U" to "프레스티지석 좌석승급",
        "F" to "일등석 보너스/좌석승급",
        "Ø" to "운항편 없음",
    )

    /** 미정의 코드는 원문 그대로 */
    fun map(frontBookingClass: String?): String =
        if (frontBookingClass.isNullOrEmpty()) "" else LABELS[frontBookingClass] ?: frontBookingClass
}
