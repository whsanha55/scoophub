package com.scoophub.kal

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

@RestController
class KalBonusController(
    private val jdbcClient: JdbcClient,
    private val crawlRunner: CrawlRunner,
    private val crawler: KalBonusCrawler,
    private val jsonMapper: tools.jackson.databind.json.JsonMapper,
    private val clock: Clock,
) {
    data class KalBonusItem(
        val key: String,
        val dateAt: Instant?,
        val updatedAt: Instant?,
        val response: tools.jackson.databind.JsonNode,
        val parsed: KalParsed,
    )

    @Tag(name = "KAL Bonus Seat")
    @Operation(summary = "대한항공 보너스 좌석 현황 조회")
    @GetMapping("/api/kal-bonus")
    fun getKalBonus(
        arrival: String? = null,
        month: String? = null,
        @RequestParam(defaultValue = "500") limit: Int = 500,
    ): ApiResponse<List<KalBonusItem>> {
        log.info { "get_kal_bonus requested: arrival=$arrival month=$month limit=$limit" }

        // month·arrival 모두 생략 → 월 목록만 메타로 반환 (UI 월 탭 헤더용, 경량)
        if (month == null && arrival == null) {
            val months = jdbcClient.sql(
                "SELECT DISTINCT substring(key from 1 for 6) AS ym FROM crawl_data " +
                    "WHERE category = :category AND purpose = :purpose ORDER BY 1",
            )
                .param("category", KalConfig.CATEGORY)
                .param("purpose", KalConfig.PURPOSE)
                .query { rs, _ -> rs.getString("ym") }
                .list()
            return ApiResponse.ok(
                emptyList(),
                ResponseMeta(clock.instant(), total = months.size, months = months),
            )
        }

        var sql = "SELECT key, date_at, updated_at, response FROM crawl_data " +
            "WHERE category = :category AND purpose = :purpose"
        if (month != null) {
            // key 포맷: {YYYYMM}-{DEPARTURE}-{ARRIVAL} → 월은 접두사 매칭
            sql += " AND key LIKE :monthPrefix"
        }
        if (arrival != null) {
            // 도착은 접미사 매칭
            sql += " AND key LIKE :arrivalSuffix"
        }
        sql += " ORDER BY date_at DESC LIMIT :limit"

        var spec = jdbcClient.sql(sql)
            .param("category", KalConfig.CATEGORY)
            .param("purpose", KalConfig.PURPOSE)
            .param("limit", limit)
        month?.let { spec = spec.param("monthPrefix", "$it%") }
        arrival?.let { spec = spec.param("arrivalSuffix", "%-${KalConfig.DEPARTURE}-${it.uppercase()}") }

        val items = spec.query { rs, _ ->
            val response = jsonMapper.readTree(rs.getString("response"))
            KalBonusItem(
                key = rs.getString("key"),
                dateAt = rs.getTimestamp("date_at")?.toInstant(),
                updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
                response = response,
                parsed = KalBonusResponseParser.parse(response),
            )
        }.list()
        return ApiResponse.ok(
            items,
            ResponseMeta(clock.instant(), total = items.size, returned = items.size),
        )
    }

    @Tag(name = "KAL Bonus Seat Crawling")
    @Operation(summary = "KAL 보너스 좌석 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/kal-bonus")
    fun triggerCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(crawler, "KAL Bonus Seat")
}
