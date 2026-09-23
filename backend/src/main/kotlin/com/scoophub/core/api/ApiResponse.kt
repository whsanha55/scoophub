package com.scoophub.core.api

import java.time.Instant

/** legacy `app/core/models.py` 대응. 필드명은 전역 SNAKE_CASE 로 직렬화된다. */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorDetail? = null,
    val meta: ResponseMeta = ResponseMeta(),
) {
    companion object {
        fun <T> ok(data: T, meta: ResponseMeta = ResponseMeta()) = ApiResponse(success = true, data = data, meta = meta)
    }
}

data class ErrorDetail(
    /** 에러 식별 코드 (예: NOT_FOUND, crawl_failed) */
    val code: String,
    val message: String,
    val detail: String? = null,
    /** 해결을 위한 다음 단계 제안 */
    val suggestion: String? = null,
)

data class ResponseMeta(
    val requestedAt: Instant = Instant.now(),
    val total: Int? = null,
    val returned: Int? = null,
    /** 월별 조회 엔드포인트에서 파라미터 생략 시 전체 월 목록(YYYYMM) */
    val months: List<String>? = null,
)
