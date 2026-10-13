package com.scoophub.hotdeal.service

import java.time.Duration

/** 스코어링 초기 정책 상수 (docs/HOTDEAL_PLAN.md 8.1). 임계치·계수는 관찰 모드 결과로 조정한다 */
object ScoringPolicy {
    const val SCORING_VERSION = "v1"

    // 웹 추천 임계치 (임시 시작값)
    const val WEB_THRESHOLD = 3.0

    // 재평가 대상 신선도 기간 (zod 핫딜 3일 기준)
    val FRESHNESS: Duration = Duration.ofHours(72)

    // 콜드스타트: 관심 항목이 이 수 미만이면 인기 기반 피드를 함께 노출한다 (8.3)
    const val COLDSTART_PROFILE_MIN = 5

    // 콜드스타트 인기 노출 상위 노출 수
    const val POPULAR_FEED_SIZE = 10

    // 인기 보너스: votes * 계수, 상한 적용 (소스별 계수는 소스 확장 시 분리한다)
    const val POPULARITY_COEFFICIENT = 0.1
    const val POPULARITY_CAP = 2.0

    // 피드백 학습 시 유효 가중치(seed + learned) 하한·상한 (8.2)
    const val WEIGHT_MIN = 0.0
    const val WEIGHT_MAX = 5.0

    // 웹에서 추가한 관심 항목의 기본 seed_weight. 매칭 1개만으로 웹 임계치에 닿게 한다 (14절)
    const val DEFAULT_SEED_WEIGHT = 3.0
}
