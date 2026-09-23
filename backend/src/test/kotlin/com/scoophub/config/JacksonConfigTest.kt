package com.scoophub.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

@JsonTest
class JacksonConfigTest(@Autowired private val mapper: JsonMapper) {

    data class Meta(val requestedAt: OffsetDateTime, val total: Int?)

    @Test
    fun `snake_case 필드명, ISO-8601 날짜, null 필드 유지 - legacy Pydantic 응답과 동일`() {
        val meta = Meta(OffsetDateTime.of(2026, 9, 23, 5, 0, 0, 0, ZoneOffset.UTC), null)

        assertEquals(
            """{"requested_at":"2026-09-23T05:00:00Z","total":null}""",
            mapper.writeValueAsString(meta),
        )
    }
}
