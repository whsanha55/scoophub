package com.scoophub.hotdeal.vo

/** hotdeal_feedback_log 항목·프로필별 delta 합 프로젝션 */
interface ItemProfileDeltaRow {
    val feedItemId: Long
    val profileId: Long
    val delta: Double
}
