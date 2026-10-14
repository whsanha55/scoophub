package com.scoophub.hotdeal.facade

import com.scoophub.global.crawl.CrawlResult
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.service.HotdealScoringService
import com.scoophub.hotdeal.vo.HotdealItemUpsert
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/** 소스별 크롤러 공통 흐름: 목록 받기 → hotdeal_item 저장 → 추천 재평가 → 관련성·제외 판정 */
@Component
class HotdealCollectFacade(
    private val itemService: HotdealItemService,
    private val scoringService: HotdealScoringService,
    private val judgmentFacade: HotdealJudgmentFacade,
) {

    fun collect(source: String, fetch: () -> List<HotdealItemUpsert>): CrawlResult {
        val items = try {
            fetch()
        } catch (e: Exception) {
            log.error(e) { "$source fetch failed: ${e.message}" }
            return CrawlResult(errors = listOf(e.message ?: e.toString()))
        }
        // HTTP 200 이어도 마크업 변경·차단 페이지면 0건이 된다. crawl_logs 에서 보이도록 partial 로 남긴다.
        if (items.isEmpty()) {
            return CrawlResult(errors = listOf("parsed 0 deals — 마크업 변경 또는 차단 여부 확인 필요"))
        }
        val itemsNew = itemService.saveAll(items)

        // 수집 주기마다 신선도 기간 내 항목을 몰아서 재평가·판정한다 (8.4, 8.5). 실패해도 수집 결과는 남긴다.
        val errors = listOfNotNull(
            runStep("scoring") { scoringService.evaluateRecentItems() },
            runStep("judgment") { judgmentFacade.judgePending() },
        )
        return CrawlResult(itemsFetched = items.size, itemsNew = itemsNew, errors = errors)
    }

    /** 실패하면 오류 메시지, 성공하면 null */
    private fun runStep(step: String, block: () -> Unit): String? = try {
        block()
        null
    } catch (e: Exception) {
        log.error(e) { "hotdeal $step failed: ${e.message}" }
        "$step failed: ${e.message ?: e}"
    }
}
