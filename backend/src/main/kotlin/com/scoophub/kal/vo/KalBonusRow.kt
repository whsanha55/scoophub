package com.scoophub.kal.vo

import tools.jackson.databind.JsonNode
import java.time.Instant

/** crawl_data(kal) 조회 프로젝션 */
data class KalBonusRow(val key: String, val dateAt: Instant?, val updatedAt: Instant?, val response: JsonNode)
