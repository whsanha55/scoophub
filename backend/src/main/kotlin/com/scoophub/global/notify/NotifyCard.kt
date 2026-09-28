package com.scoophub.global.notify

import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.jackson.elements
import com.scoophub.global.jackson.scalar
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * legacy `core/notify/card.py` — 발신 카드 포맷팅 + 카테고리별 enrich.
 * enrich 반환 null 이면 발신 스킵.
 *
 * - news        : feed_news(importance>=4) 탑5 제목+요약+원문 — news 도메인 이관 시 엔티티로 전환 예정
 * - weather     : crawl_data(weather, snapshot) → 온도/대기질/주간예보
 * - kal_bonus   : crawl_data(kal, bonus_seat) → 2027 Q1 프레스티지(P) 잔석 나라별 집계
 * - community/feed: crawl_data batch(updated_at DESC) → 도메인 sort key 탑5
 */
@Component
class NotifyCard(private val crawlDataRepository: CrawlDataRepository, private val jdbcClient: JdbcClient) {
    /** 카테고리별 enrich 진입. null 반환 시 발신 스킵 */
    fun enrich(category: String, detail: String, text: String, newIds: List<Long>): String? {
        if (category == "news") {
            val body = enrichNews(newIds) ?: return null
            // count = 실제 표시된 importance>=4 라인 수
            return formatNews(detail, countLines(body), body)
        }
        if (category == "weather") {
            val body = enrichWeather() ?: return null
            return formatDefault("weather", detail, 0, body)
        }
        if (category == "kal_bonus") {
            val body = enrichKal() ?: return null
            return formatDefault("kal_bonus", detail, 0, body)
        }
        if (NAME_PURPOSE.containsKey(category)) {
            val body = enrichBatch(category) ?: return null
            return formatDefault(category, detail, countLines(body), body)
        }
        // 미정의 카테고리 — base 카드 그대로(degrade)
        return text
    }

    // ── news ──────────────────────────────────────────────────────────

    private data class NewsRow(val title: String?, val summary: String?, val url: String?)

    /** feed_news 신규 id → importance>=4 탑5 제목+요약+원문. 0건 → null */
    private fun enrichNews(newIds: List<Long>): String? {
        if (newIds.isEmpty()) {
            return null
        }
        val rows = jdbcClient.sql(
            """
            SELECT title, summary, url FROM feed_news
            WHERE id IN (:ids) AND importance >= 4 AND summary IS NOT NULL AND summary <> ''
            ORDER BY importance DESC NULLS LAST LIMIT 5
            """,
        )
            .param("ids", newIds)
            .query { rs, _ -> NewsRow(rs.getString("title"), rs.getString("summary"), rs.getString("url")) }
            .list()

        val lines = rows.mapNotNull { row ->
            val title = row.title?.trim().orEmpty()
            if (title.isEmpty()) {
                return@mapNotNull null
            }
            var line = "\n• <b>${escapeHtml(title)}</b>"
            val summary = row.summary?.trim().orEmpty()
            if (summary.isNotEmpty()) {
                line += "\n  ${escapeHtml(summary.take(150))}"
            }
            val url = row.url?.trim().orEmpty()
            if (url.isNotEmpty()) {
                line += """ <a href="${escapeHtml(url)}">원문</a>"""
            }
            line
        }
        if (lines.isEmpty()) {
            return null
        }
        return lines.joinToString("")
    }

    // ── weather ───────────────────────────────────────────────────────

    /** crawl_data(weather, snapshot) 최신 → 현재날씨+대기질+주간예보 */
    private fun enrichWeather(): String? {
        val row = crawlDataRepository.findFirstByCategoryAndPurposeOrderByDateAtDesc("weather", "snapshot")
            ?: return null
        val resp = row.response

        val lines = mutableListOf<String>()

        // 1줄: 온도(체감) · 상태 · 습도
        val parts = mutableListOf<String>()
        resp.scalar("temperature")?.let { temp ->
            var t = "${escapeHtml(temp)}°C"
            resp.scalar("feels_like")?.let { feels -> t += "(체감 ${escapeHtml(feels)})" }
            parts.add(t)
        }
        resp.scalar("condition")?.let { parts.add(escapeHtml(it)) }
        resp.scalar("humidity")?.let { parts.add("습도 ${escapeHtml(it)}%") }
        if (parts.isNotEmpty()) {
            lines.add(parts.joinToString(" · "))
        }

        // 2줄: 오늘 최저/최고 (weekly_forecast[0] = 오늘)
        val weekly = resp["weekly_forecast"].elements()
        val today = weekly.firstOrNull()
        val todayLo = today?.scalar("mintempC")
        val todayHi = today?.scalar("maxtempC")
        if (todayLo != null && todayHi != null) {
            lines.add("오늘 최저 ${escapeHtml(todayLo)}°/최고 ${escapeHtml(todayHi)}°")
        }

        // 3줄: 미세먼지 · 초미세먼지 · 자외선 (등급 + 수치)
        val airParts = mutableListOf<String>()
        resp.scalar("pm10_grade")?.let { grade ->
            var seg = "미세먼지 ${escapeHtml(grade)}"
            num(resp["pm10"])?.let { seg += "($it)" }
            airParts.add(seg)
        }
        resp.scalar("pm25_grade")?.let { grade ->
            var seg = "초미세먼지 ${escapeHtml(grade)}"
            num(resp["pm25"])?.let { seg += "($it)" }
            airParts.add(seg)
        }
        resp.scalar("uv_grade")?.let { airParts.add("자외선 ${escapeHtml(it)}") }
        if (airParts.isNotEmpty()) {
            lines.add(airParts.joinToString(" · "))
        }

        // 4줄: 예보 (오늘[0]은 위 오늘 줄과 중복 → 제외, 내일~모레)
        val forecast = weekly.drop(1)
        if (forecast.isNotEmpty()) {
            val dayStrs = forecast.mapNotNull { day ->
                val wday = weekdayKo(day.scalar("date").orEmpty())
                val mn = day.scalar("mintempC")
                val mx = day.scalar("maxtempC")
                val rainPct = day["hourly"].elements()
                    .mapNotNull { it.scalar("chanceofrain")?.toIntOrNull() }
                    .maxOrNull()

                var seg = wday
                if (mn != null && mx != null) {
                    seg += " ${escapeHtml(mn)}/${escapeHtml(mx)}"
                }
                if (rainPct != null && rainPct >= 30) {
                    seg += " 비${escapeHtml(rainPct)}%"
                }
                seg.trim().ifEmpty { null }
            }
            if (dayStrs.isNotEmpty()) {
                lines.add("예보: " + dayStrs.joinToString(" · "))
            }
        }

        if (lines.isEmpty()) {
            return null
        }
        return lines.joinToString("\n")
    }

    // ── community/feed batch ──────────────────────────────────────────

    /** community/feed 공용 — crawl_data batch(updated_at DESC) → sort key 탑5 */
    private fun enrichBatch(name: String): String? {
        val (category, purpose) = NAME_PURPOSE.getValue(name)
        val items = crawlDataRepository
            .findFirst50ByCategoryAndPurposeOrderByUpdatedAtDesc(category, purpose)
            .map { it.response }
        if (items.isEmpty()) {
            return null
        }

        val top5 = SORT_KEY[name]?.let { sortKey ->
            items.sortedByDescending { it[sortKey]?.asDouble(0.0) ?: 0.0 }
        } ?: items
        val top = top5.take(5)

        val lines = top.mapNotNull { lineFor(name, it) }
        if (lines.isEmpty()) {
            return null
        }
        return lines.joinToString("")
    }

    // ── kal_bonus ─────────────────────────────────────────────────────

    /** crawl_data(kal, bonus_seat) → 2027 Q1 프레스티지(P) 잔석 나라별 집계 */
    private fun enrichKal(): String? {
        // arr(공항코드) → 2027 Q1 P 잔석 건수
        val seats = mutableMapOf<String, Int>()
        val (ymLo, ymHi) = KAL_PRESTIGE_Q1

        for (row in crawlDataRepository.findFirst50ByCategoryAndPurposeOrderByUpdatedAtDesc(
            KAL_CATEGORY,
            KAL_PURPOSE,
        )) {
            val r = row.response
            val arr = r.scalar("arrivalAirport").orEmpty()
            for (flightDay in r["flightList"].elements()) {
                val ym = flightDay.scalar("departureDate").orEmpty().take(6) // YYYYMMDD → YYYYMM
                if (ym < ymLo || ym > ymHi) {
                    continue
                }
                for (detail in flightDay["flightDetailList"].elements()) {
                    if (detail.scalar("frontBookingClass") != "P") {
                        continue
                    }
                    if (!hasSeat(detail["availableSeat"])) {
                        continue
                    }
                    seats[arr] = (seats[arr] ?: 0) + 1
                }
            }
        }

        if (seats.isEmpty()) {
            return null
        }

        // 잔석 수 기준 정렬 후 top8 (4096 한도 방지)
        return seats.entries
            .sortedByDescending { it.value }
            .take(8)
            .joinToString("") { (arr, cnt) ->
                val city = KAL_ARR_CITY[arr]
                val label = if (city != null) "${escapeHtml(city)}(${escapeHtml(arr)})" else escapeHtml(arr)
                "\n• $label: P 잔석 ${escapeHtml(cnt)}건"
            }
    }

    companion object {
        /** 큰 섹터(category)별 토픽 이모지 — formatCard 표식 */
        private val EMOJI = mapOf(
            "news" to "📰",
            "weather" to "🌤",
            "stock" to "📈",
            "community" to "👥",
            "feed" to "📜",
            "kal_bonus" to "✈️",
        )

        /** 크롤러 name → (crawl_data category, purpose). batch 조회 키 */
        val NAME_PURPOSE = mapOf(
            "weather" to ("weather" to "snapshot"),
            "hacker_news" to ("community" to "hackernews"),
            "product_hunt" to ("community" to "producthunt"),
            "github_trending" to ("community" to "github"),
            "devto_hashnode" to ("feed" to "devblog"),
            "tech_newsletter" to ("feed" to "newsletter"),
            "arxiv" to ("feed" to "arxiv"),
            "youtube_trending" to ("feed" to "youtube"),
        )

        /** 도메인별 탑5 정렬 기준 (response JSONB 안 필드 — DB 정렬 불가, 앱 단 정렬). null=최신순 그대로 */
        private val SORT_KEY = mapOf(
            "hacker_news" to "score",
            "product_hunt" to "votes_count",
            "github_trending" to "stars",
            "devto_hashnode" to "reactions_count",
            "youtube_trending" to "view_count",
        )

        // kal_bonus — config 상수와 동일 (순환 참조 방지용 로컬 복제. kal 도메인 이관 시 참조 전환)
        private const val KAL_CATEGORY = "kal"
        private const val KAL_PURPOSE = "bonus_seat"

        /** arrival(공항코드) → 도시명 */
        private val KAL_ARR_CITY = mapOf(
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

        /** 2027 Q1 잔석 집계 대상 기간(YYYYMM) */
        private val KAL_PRESTIGE_Q1 = "202701" to "202703"

        /** HTML 특수문자 이스케이프. 동적 텍스트 전부 적용 */
        fun escapeHtml(s: Any?): String = when (s) {
            null -> ""
            else -> s.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }

        /** base 카드 — 헤더(emoji+category+detail) + 신규 건수 */
        fun formatCard(category: String, detail: String, itemsNew: Int, itemsFetched: Int = 0): String {
            var head = "${EMOJI[category] ?: "🔔"} [${escapeHtml(category)}"
            if (detail.isNotEmpty()) {
                head += " · ${escapeHtml(detail)}"
            }
            head += "]"
            var body = "신규 ${itemsNew}건"
            if (itemsFetched != 0) {
                body += " (총 ${itemsFetched}건)"
            }
            return "$head\n$body"
        }

        /** community/feed/weather/kal 공용 카드 — 헤더 + body */
        fun formatDefault(name: String, detail: String, count: Int, body: String): String {
            val (category, _) = NAME_PURPOSE[name] ?: (name to "")
            var head = "${EMOJI[category] ?: "🔔"} [${escapeHtml(name)}"
            if (detail.isNotEmpty()) {
                head += " · ${escapeHtml(detail)}"
            }
            head += "]"
            val countPart = if (count != 0) "신규 ${count}건\n" else ""
            return "$head\n$countPart$body"
        }

        /** news 전용 카드 — importance 4+ 표식 헤더 + body */
        fun formatNews(detail: String, count: Int, body: String): String {
            var head = "${EMOJI.getValue("news")} [news"
            if (detail.isNotEmpty()) {
                head += " · ${escapeHtml(detail)}"
            }
            head += "] — 중요도 4+ ${count}건"
            return "$head\n$body"
        }

        /** 'YYYY-MM-DD' → 한국 요일. 파싱 실패 시 빈 문자열 */
        fun weekdayKo(dateStr: String): String = try {
            val dow = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).dayOfWeek.value // 1=월..7=일
            listOf("월", "화", "수", "목", "금", "토", "일")[dow - 1]
        } catch (e: Exception) {
            ""
        }

        /** availableSeat → 잔석 존재 여부. API 가 문자열/정수/null 혼합 → bool() 오탐 방지 */
        fun hasSeat(value: JsonNode?): Boolean = value != null && !value.isNull && value.asInt(0) > 0

        /** 대기질 수치 정수 반올림 → str. null/변환불가 → null (단위 µg/m³) */
        fun num(value: JsonNode?): String? {
            if (value == null || value.isNull) {
                return null
            }
            val d = value.asDouble(Double.NaN)
            return if (d.isNaN()) null else Math.round(d).toString()
        }

        /** 도메인별 한 줄 카드. title/url/meta 추출. null 이면 스킵 */
        fun lineFor(name: String, r: JsonNode): String? {
            val title: String
            val url: String
            when (name) {
                "github_trending" -> {
                    title = r.scalar("fullname").orEmpty().trim()
                    url = r.scalar("url").orEmpty().trim()
                }

                "product_hunt" -> {
                    title = r.scalar("name").orEmpty().trim()
                    url = r.scalar("ph_url").orEmpty().trim()
                }

                else -> {
                    title = r.scalar("title").orEmpty().trim()
                    url = r.scalar("url").orEmpty().trim()
                }
            }
            if (title.isEmpty()) {
                return null
            }
            var line = "\n• <b>${escapeHtml(title)}</b>"
            val meta = metaFor(name, r)
            if (meta.isNotEmpty()) {
                line += " · $meta"
            }
            if (url.isNotEmpty()) {
                line += """ <a href="${escapeHtml(url)}">보기</a>"""
            }
            return line
        }

        /** 도메인별 메타(score/votes/author). 값 없으면 빈 문자열 */
        private fun metaFor(name: String, r: JsonNode): String = when (name) {
            "hacker_news" -> r.scalar("score")?.let { "${escapeHtml(it)}점" } ?: ""

            "product_hunt" -> r.scalar("votes_count")?.let { "▲${escapeHtml(it)}" } ?: ""

            "github_trending" -> r.scalar("stars")?.let { "★${escapeHtml(it)}" } ?: ""

            "devto_hashnode" -> listOfNotNull(
                r.scalar("author")?.let { escapeHtml(it) },
                r.scalar("reactions_count")?.let { "♥${escapeHtml(it)}" },
            ).joinToString(" · ")

            "tech_newsletter" -> r.scalar("source")?.let { escapeHtml(it) } ?: ""

            "arxiv" -> r.scalar("primary_category")?.let { escapeHtml(it) } ?: ""

            "youtube_trending" -> listOfNotNull(
                r.scalar("channel_title")?.let { escapeHtml(it) },
                r.scalar("view_count")?.let { "조회 ${escapeHtml(it)}" },
            ).joinToString(" · ")

            else -> ""
        }

        private fun countLines(body: String): Int = body.split("\n• ").size - 1
    }
}

/** JsonNode 확장 — python dict.get 대응: null/missing 이면 null */
private fun JsonNode?.scalar(field: String): String? {
    val node = this?.get(field) ?: return null
    if (node.isNull || node.isMissingNode) {
        return null
    }
    return node.asText()
}

private fun JsonNode?.elements(): List<JsonNode> = this?.takeIf { it.isArray }?.toList() ?: emptyList()
