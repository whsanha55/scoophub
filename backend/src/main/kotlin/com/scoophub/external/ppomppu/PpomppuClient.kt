package com.scoophub.external.ppomppu

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class PpomppuDeal(
    val sourceKey: String,
    val title: String,
    val url: String,
    val mall: String?,
    val price: Long?,
    val shippingFee: String?,
    val itemAt: Instant,
    val votes: Long,
)

/**
 * 뽐뿌 게시판 공식 RSS (rss.php?id=ppomppu). robots.txt 에서 허용된 경로다.
 * 제목 형식: "[몰] 상품명 (가격/배송)". [몰] 이 없으면 제목 전체를 상품명으로 둔다.
 */
@Component
class PpomppuClient(restClientBuilder: RestClient.Builder) {
    private val restClient = restClientBuilder.build()

    fun deals(): List<PpomppuDeal> {
        val xml = restClient.get().uri(RSS_URL).retrieve().body(String::class.java) ?: return emptyList()
        return parse(xml)
    }

    /** RSS XML → 딜 목록. 테스트를 위해 분리 */
    fun parse(xml: String): List<PpomppuDeal> = Jsoup.parse(xml, "", Parser.xmlParser())
        .select("item")
        .mapNotNull(::toDeal)

    private fun toDeal(item: Element): PpomppuDeal? {
        val rawTitle = item.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sourceKey = item.selectFirst("link")?.text()
            ?.let { NO_PARAM.find(it)?.groupValues?.get(1) }
            ?: return null
        val itemAt = item.selectFirst("pubDate")?.text()?.let(::parsePubDate) ?: return null
        val title = TITLE_FORMAT.matchEntire(rawTitle)
        val (price, shippingFee) = title?.groupValues?.get(3)
            ?.takeIf { it.isNotBlank() }
            ?.let(::parsePriceAndShipping)
            ?: (null to null)

        return PpomppuDeal(
            sourceKey = sourceKey,
            title = title?.groupValues?.get(2)?.trim() ?: rawTitle,
            url = DEAL_URL + sourceKey,
            mall = title?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            price = price,
            shippingFee = shippingFee,
            itemAt = itemAt,
            votes = item.selectFirst("hits")?.text()?.let(::parseVotes) ?: 0L,
        )
    }

    private fun parsePubDate(value: String): Instant? = runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull()

    /**
     * 괄호 안 "가격/배송" → (가격, 배송). 실제 표기 예:
     * "20,020원/무배", "16,600/무료", "42,315원 외/무료", "다양/무료", "20,180원,무료배송"
     */
    private fun parsePriceAndShipping(text: String): Pair<Long?, String?> {
        val priceMatch = LEADING_PRICE.find(text.trim())
        val price = priceMatch?.groupValues?.get(1)?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }?.toLong()
        val shipping = if (text.contains('/')) {
            text.substringAfterLast('/')
        } else {
            priceMatch?.let { text.trim().removePrefix(it.value) } ?: ""
        }.trim(' ', ',').takeIf { it.isNotEmpty() }
        return price to shipping
    }

    // hits 는 "[댓글|조회|추천|비추천]" 형식으로 보고 세 번째 값을 추천수로 쓴다
    private fun parseVotes(text: String): Long? = text.trim().removePrefix("[").removeSuffix("]")
        .split("|")
        .getOrNull(VOTES_INDEX)
        ?.trim()
        ?.toLongOrNull()

    companion object {
        private const val RSS_URL = "https://www.ppomppu.co.kr/rss.php?id=ppomppu"
        private const val DEAL_URL = "https://www.ppomppu.co.kr/zboard/view.php?id=ppomppu&no="
        private const val VOTES_INDEX = 2
        private val NO_PARAM = Regex("[?&]no=(\\d+)")

        // "[몰] 상품명 (가격/배송)". 괄호가 없는 제목도 있다
        private val TITLE_FORMAT = Regex("^\\[([^\\]]*)]\\s*(.+?)\\s*(?:\\(([^()]*)\\))?\\s*$")
        private val LEADING_PRICE = Regex("^([\\d,]+)\\s*원?")
    }
}
