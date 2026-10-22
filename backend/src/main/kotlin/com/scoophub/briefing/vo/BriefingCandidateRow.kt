package com.scoophub.briefing.vo

import com.scoophub.briefing.enums.BriefingSectionEnum

/**
 * 아침 브리핑 후보 한 건. metric 은 묶음별 기준과 비교하는 값이다
 * (뉴스 중요도, HN 점수, GitHub 하루 스타 증가, Dev.to 반응 수).
 */
data class BriefingCandidateRow(
    val section: BriefingSectionEnum,
    val key: String,
    val title: String,
    val url: String?,
    val metric: Long,
    val description: String? = null,
) {
    /** 이미 나온 항목 기록의 키. 묶음마다 원본 키 체계가 달라 앞에 묶음을 붙인다 */
    val briefingKey: String
        get() = "${section.keyPrefix}:$key"
}
