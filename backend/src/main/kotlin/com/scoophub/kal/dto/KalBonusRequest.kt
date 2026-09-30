package com.scoophub.kal.dto

import io.swagger.v3.oas.annotations.media.Schema

data class KalBonusRequest(
    @field:Schema(description = "도착 공항 코드 (대소문자 무관)")
    val arrival: String? = null,

    @field:Schema(description = "조회 월 (YYYYMM)")
    val month: String? = null,

    @field:Schema(description = "최대 건수")
    val limit: Int = 500,
) {
    /** month·arrival 모두 생략 → 월 목록만 조회 */
    fun isMonthsOnly() = month == null && arrival == null

    fun toCondition() = KalBonusCondition(arrival = arrival?.uppercase(), month = month, limit = limit)
}

data class KalBonusCondition(val arrival: String?, val month: String?, val limit: Int)
