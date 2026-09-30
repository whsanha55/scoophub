package com.scoophub.kal

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.kal.dto.KalBonusRequest
import com.scoophub.kal.dto.KalBonusResponse
import com.scoophub.kal.service.KalBonusService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

private val log = KotlinLogging.logger {}

@RestController
class KalBonusController(
    private val kalBonusService: KalBonusService,
    private val crawlRunner: CrawlRunner,
    private val crawler: KalBonusCrawler,
    private val clock: Clock,
) {
    @Tag(name = "KAL Bonus Seat")
    @Operation(summary = "대한항공 보너스 좌석 현황 조회")
    @GetMapping("/api/kal-bonus")
    fun getKalBonus(@ParameterObject request: KalBonusRequest): ApiResponse<List<KalBonusResponse>> {
        log.info { "get_kal_bonus requested: arrival=${request.arrival} month=${request.month} limit=${request.limit}" }

        // month·arrival 모두 생략 → 월 목록만 메타로 반환 (UI 월 탭 헤더용, 경량)
        if (request.isMonthsOnly()) {
            val months = kalBonusService.findMonths()
            return ApiResponse.ok(
                emptyList(),
                ResponseMeta(clock.instant(), total = months.size, months = months),
            )
        }

        val items = kalBonusService.find(request.toCondition())
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
