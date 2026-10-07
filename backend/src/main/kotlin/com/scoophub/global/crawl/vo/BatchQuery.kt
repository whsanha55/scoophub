package com.scoophub.global.crawl.vo

import tools.jackson.databind.JsonNode
import java.time.Instant

/** crawl_data 최신 배치 조회 결과 row */
data class BatchRow(val id: Long, val key: String, val dateAt: Instant, val response: JsonNode)

/** response JSONB 필드 필터. 필드명은 코드 상수만 허용(사용자 입력은 value 로만) */
sealed interface BatchFilter {
    val value: Any

    data class TextEq(val field: String, override val value: String) : BatchFilter

    /** ILIKE — value 에 와일드카드 포함(예: %kw%) */
    data class TextLike(val field: String, override val value: String) : BatchFilter

    /** JSONB 배열 포함 — value 는 배열 원소 하나(예: "python" → tags @> '["python"]'::jsonb) */
    data class JsonArrayContains(val field: String, override val value: String) : BatchFilter

    data class IntGte(val field: String, override val value: Int) : BatchFilter

    data class TimestamptzGte(val field: String, override val value: Instant) : BatchFilter
}

sealed interface BatchSortKey {
    data class IntDesc(val field: String) : BatchSortKey

    /** 조회수 등 int 범위 초과 가능 필드 */
    data class LongDesc(val field: String) : BatchSortKey

    data object DateAtDesc : BatchSortKey
}
