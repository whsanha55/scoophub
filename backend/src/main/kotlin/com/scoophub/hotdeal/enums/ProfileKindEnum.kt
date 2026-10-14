package com.scoophub.hotdeal.enums

/** hotdeal_interest_profile.kind. EXCLUDE 는 가중치 없이 제외 판정(8.4)에만 쓴다 */
enum class ProfileKindEnum {
    KEYWORD,
    CATEGORY,
    SOURCE,
    EXCLUDE,
    ;

    /** 관심 항목(콜드스타트 판단·피드백 학습 대상) 여부 */
    val isInterest: Boolean
        get() = this == KEYWORD || this == CATEGORY
}
