package com.scoophub.hotdeal.enums

/** hotdeal_feedback.kind — delta 는 피드백 1건이 매칭 항목 전체에 주는 학습 변화량 (8.2) */
enum class FeedbackKindEnum(val delta: Double) {
    LIKE(0.5),
    DISLIKE(-0.5),
}
