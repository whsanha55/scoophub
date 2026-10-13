package com.scoophub.hotdeal

import com.scoophub.external.zod.ZodClient
import com.scoophub.external.zod.ZodDeal
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.hotdeal.enums.HotdealSourceEnum
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.service.HotdealScoringService
import com.scoophub.hotdeal.vo.HotdealItemUpsert
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/** 알구더(zod.kr/deal) 목록 → hotdeal_item → 추천 재평가 */
@Component
class ZodCrawler(
    private val client: ZodClient,
    private val itemService: HotdealItemService,
    private val scoringService: HotdealScoringService,
) : Crawler {
    override val name = "hotdeal_zod"

    override fun fetch(): CrawlResult {
        val deals = try {
            client.deals()
        } catch (e: Exception) {
            log.error(e) { "zod fetch failed: ${e.message}" }
            return CrawlResult(errors = listOf(e.message ?: e.toString()))
        }
        // HTTP 200 이어도 마크업 변경·차단 페이지면 0건이 된다. crawl_logs 에서 보이도록 partial 로 남긴다.
        if (deals.isEmpty()) {
            return CrawlResult(errors = listOf("parsed 0 deals — 마크업 변경 또는 차단 여부 확인 필요"))
        }
        val itemsNew = itemService.saveAll(deals.map { it.toUpsert() })

        // 수집 주기마다 신선도 기간 내 항목을 몰아서 재평가한다 (8.5). 실패해도 수집 결과는 남긴다.
        val errors = try {
            scoringService.evaluateRecentItems()
            emptyList()
        } catch (e: Exception) {
            log.error(e) { "hotdeal scoring failed: ${e.message}" }
            listOf("scoring failed: ${e.message ?: e}")
        }
        return CrawlResult(itemsFetched = deals.size, itemsNew = itemsNew, errors = errors)
    }

    private fun ZodDeal.toUpsert() = HotdealItemUpsert(
        source = HotdealSourceEnum.ZOD,
        provider = PROVIDER,
        sourceKey = sourceKey,
        title = title,
        url = url,
        itemAt = itemAt,
        extra = mapOf(
            "mall" to mall,
            "price" to price,
            "shipping_fee" to shippingFee,
            "votes" to votes,
        ),
    )

    companion object {
        private const val PROVIDER = "zod"
    }
}
