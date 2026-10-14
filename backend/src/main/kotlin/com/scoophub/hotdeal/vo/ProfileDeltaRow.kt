package com.scoophub.hotdeal.vo

/** hotdeal_feedback_log 프로필별 delta 합 프로젝션 */
interface ProfileDeltaRow {
    val profileId: Long
    val delta: Double
}
