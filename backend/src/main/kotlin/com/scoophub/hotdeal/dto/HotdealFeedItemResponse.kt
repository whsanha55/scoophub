package com.scoophub.hotdeal.dto

import com.scoophub.hotdeal.entity.HotdealFeedItemEntity
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant

data class HotdealFeedItemResponse(
    @field:Schema(description = "피드 항목 ID", example = "1")
    val id: Long,
    @field:Schema(description = "수집 소스", example = "ZOD")
    val source: String,
    @field:Schema(description = "제목", example = "WD SN850X 2TB")
    val title: String,
    @field:Schema(description = "원문 링크", example = "https://zod.kr/deal/8887617")
    val url: String,
    @field:Schema(description = "판매처", example = "지마켓")
    val mall: String?,
    @field:Schema(description = "가격(원)", example = "149000")
    val price: Long?,
    @field:Schema(description = "배송비", example = "무료")
    val shippingFee: String?,
    @field:Schema(description = "추천수", example = "12")
    val votes: Long,
    @field:Schema(description = "원본 게시 시각")
    val itemAt: Instant,
    @field:Schema(description = "추천 시각")
    val recommendedAt: Instant,
    @field:Schema(description = "추천 점수", example = "3.5")
    val score: Double,
    @field:Schema(description = "콜드스타트 인기 노출 여부")
    val popular: Boolean,
    @field:Schema(description = "추천 이유", example = "관심 매칭(SSD)")
    val reason: String,
    @field:Schema(description = "매칭된 관심 항목")
    val matchedTerms: List<MatchedTerm>,
    @field:Schema(description = "추천 조건 충족 여부. false 면 재평가에서 조건을 벗어난 항목")
    val active: Boolean,
    @field:Schema(description = "현재 피드백 (없으면 null)", example = "LIKE")
    val feedback: FeedbackKindEnum?,
) {
    data class MatchedTerm(
        @field:Schema(description = "프로필 종류", example = "KEYWORD")
        val kind: String,
        @field:Schema(description = "값", example = "SSD")
        val value: String,
        @field:Schema(description = "당시 가중치", example = "2.0")
        val weight: Double,
    )

    companion object {
        fun from(feedItem: HotdealFeedItemEntity, feedback: FeedbackKindEnum?): HotdealFeedItemResponse {
            val item = feedItem.item
            val extra = item.extra
            val terms = feedItem.matchedTerms
            // Jackson 3 JsonNode 의 멤버 map 대신 Kotlin Iterable.map 을 쓰기 위해 타입을 넓힌다
            val termNodes: Iterable<JsonNode> = terms.path("terms")
            return HotdealFeedItemResponse(
                id = requireNotNull(feedItem.id),
                source = item.source.name,
                title = item.title,
                url = item.url,
                mall = extra.scalar("mall"),
                price = extra.path("price").takeIf { it.isNumber }?.asLong(),
                shippingFee = extra.scalar("shipping_fee"),
                votes = extra.path("votes").asLong(0L),
                itemAt = item.itemAt,
                recommendedAt = feedItem.recommendedAt,
                score = feedItem.score,
                popular = feedItem.popular,
                reason = terms.scalar("reason").orEmpty(),
                matchedTerms = termNodes.map {
                    MatchedTerm(
                        kind = it.scalar("kind").orEmpty(),
                        value = it.scalar("value").orEmpty(),
                        weight = it.path("weight").asDouble(0.0),
                    )
                },
                active = feedItem.active,
                feedback = feedback,
            )
        }

        private fun JsonNode.scalar(field: String): String? =
            this[field]?.takeIf { !it.isNull && !it.isMissingNode }?.asText()
    }
}
