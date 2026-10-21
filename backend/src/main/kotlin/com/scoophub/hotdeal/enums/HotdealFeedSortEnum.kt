package com.scoophub.hotdeal.enums

import org.springframework.data.domain.Sort

/** GET /api/hotdeal/feed 의 sort 파라미터 (소문자로 받는다) */
enum class HotdealFeedSortEnum(val sort: Sort) {
    RECOMMENDED(Sort.by(Sort.Direction.DESC, "score")),
    LATEST(Sort.by(Sort.Direction.DESC, "item.itemAt")),
    ;

    companion object {
        fun fromParam(value: String): HotdealFeedSortEnum? = entries.find { it.name.equals(value, ignoreCase = true) }
    }
}
