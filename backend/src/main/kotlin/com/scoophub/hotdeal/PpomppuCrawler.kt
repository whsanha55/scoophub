package com.scoophub.hotdeal

import com.scoophub.external.ppomppu.PpomppuClient
import com.scoophub.external.ppomppu.PpomppuDeal
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.hotdeal.enums.HotdealSourceEnum
import com.scoophub.hotdeal.service.HotdealCollectService
import com.scoophub.hotdeal.vo.HotdealItemUpsert
import org.springframework.stereotype.Component

/** 뽐뿌 게시판 RSS → hotdeal_item → 추천 재평가 */
@Component
class PpomppuCrawler(private val client: PpomppuClient, private val collectService: HotdealCollectService) :
    Crawler {
    override val name = "hotdeal_ppomppu"

    override fun fetch(): CrawlResult = collectService.collect(name) { client.deals().map { it.toUpsert() } }

    private fun PpomppuDeal.toUpsert() = HotdealItemUpsert(
        source = HotdealSourceEnum.PPOMPPU,
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
        private const val PROVIDER = "ppomppu"
    }
}
