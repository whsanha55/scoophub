package com.scoophub.hotdeal.dto

import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
import io.swagger.v3.oas.annotations.media.Schema

data class HotdealProfileCreateRequest(
    @field:Schema(description = "종류", example = "KEYWORD")
    val kind: ProfileKindEnum,
    @field:Schema(description = "값", example = "SSD")
    val value: String,
    @field:Schema(description = "수동 가중치 (생략하면 기본값 3.0)", example = "3.0")
    val seedWeight: Double? = null,
)

data class HotdealProfileResponse(
    @field:Schema(description = "프로필 ID", example = "1")
    val id: Long,
    @field:Schema(description = "종류", example = "KEYWORD")
    val kind: ProfileKindEnum,
    @field:Schema(description = "값", example = "SSD")
    val value: String,
    @field:Schema(description = "수동 가중치", example = "3.0")
    val seedWeight: Double,
    @field:Schema(description = "피드백 학습 가중치", example = "0.25")
    val learnedWeight: Double,
    @field:Schema(description = "유효 가중치 (seed + learned)", example = "3.25")
    val effectiveWeight: Double,
) {
    companion object {
        fun from(profile: HotdealInterestProfileEntity) = HotdealProfileResponse(
            id = requireNotNull(profile.id),
            kind = profile.kind,
            value = profile.value,
            seedWeight = profile.seedWeight,
            learnedWeight = profile.learnedWeight,
            effectiveWeight = profile.effectiveWeight,
        )
    }
}

data class HotdealFeedbackRequest(
    @field:Schema(description = "피드백", example = "LIKE")
    val kind: FeedbackKindEnum,
)

data class HotdealFeedbackResponse(
    @field:Schema(description = "피드 항목 ID", example = "1")
    val feedItemId: Long,
    @field:Schema(description = "현재 피드백 (취소면 null)", example = "LIKE")
    val feedback: FeedbackKindEnum?,
)
