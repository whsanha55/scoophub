package com.scoophub.hotdeal.vo

import com.scoophub.hotdeal.enums.HotdealSourceEnum
import java.time.Instant

/** 소스별 수집 결과를 hotdeal_item 한 행으로 맞춘 값. extra 는 소스별 변경 가능한 메타데이터 */
data class HotdealItemUpsert(
    val source: HotdealSourceEnum,
    val provider: String,
    val sourceKey: String,
    val title: String,
    val url: String,
    val itemAt: Instant,
    val extra: Map<String, Any?>,
)
