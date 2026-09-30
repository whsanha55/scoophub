package com.scoophub.kal.dto

import com.scoophub.kal.KalParsed
import com.scoophub.kal.vo.KalBonusRow
import tools.jackson.databind.JsonNode
import java.time.Instant

data class KalBonusResponse(
    val key: String,
    val dateAt: Instant?,
    val updatedAt: Instant?,
    val response: JsonNode,
    val parsed: KalParsed,
) {
    companion object {
        fun from(row: KalBonusRow, parsed: KalParsed) = KalBonusResponse(
            key = row.key,
            dateAt = row.dateAt,
            updatedAt = row.updatedAt,
            response = row.response,
            parsed = parsed,
        )
    }
}
