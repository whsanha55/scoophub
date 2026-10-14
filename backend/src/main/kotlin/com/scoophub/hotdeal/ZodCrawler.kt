package com.scoophub.hotdeal

import com.scoophub.external.zod.ZodClient
import com.scoophub.external.zod.ZodDeal
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.hotdeal.enums.HotdealSourceEnum
import com.scoophub.hotdeal.facade.HotdealCollectFacade
import com.scoophub.hotdeal.vo.HotdealItemUpsert
import org.springframework.stereotype.Component

/**
 * 알구더(zod.kr/deal) 목록 → hotdeal_item → 추천 재평가.
 * 2026-10 기준 Cloudflare 봇 차단(403)으로 스케줄은 꺼 두었다 (V42). 차단이 풀리면 crawl_schedule 에서 켠다.
 */
@Component
class ZodCrawler(private val client: ZodClient, private val collectFacade: HotdealCollectFacade) : Crawler {
    override val name = "hotdeal_zod"

    override fun fetch(): CrawlResult = collectFacade.collect(name) { client.deals().map { it.toUpsert() } }

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
