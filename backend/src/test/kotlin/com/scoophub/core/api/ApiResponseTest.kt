package com.scoophub.core.api

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** legacy tests/test_models.py 포팅 — 직렬화 형태까지 확인 */
@JsonTest
class ApiResponseTest(@Autowired private val mapper: JsonMapper) {

    private val at = Instant.parse("2026-09-23T05:00:00Z")

    @Test
    fun `성공 응답 - error, meta 기본값 null 필드 포함`() {
        val json = mapper.writeValueAsString(ApiResponse.ok(mapOf("key" to "value"), ResponseMeta(requestedAt = at)))

        assertEquals(
            """{"success":true,"data":{"key":"value"},"error":null,""" +
                """"meta":{"requested_at":"2026-09-23T05:00:00Z","total":null,"returned":null,"months":null}}""",
            json,
        )
    }

    @Test
    fun `에러 응답`() {
        val resp = ApiResponse<Nothing>(
            success = false,
            error = ErrorDetail("INVALID_PARAM", "Invalid parameter", "minutes must be positive", "Use a positive integer"),
            meta = ResponseMeta(requestedAt = at),
        )

        assertEquals(
            """{"success":false,"data":null,"error":{"code":"INVALID_PARAM","message":"Invalid parameter",""" +
                """"detail":"minutes must be positive","suggestion":"Use a positive integer"},""" +
                """"meta":{"requested_at":"2026-09-23T05:00:00Z","total":null,"returned":null,"months":null}}""",
            mapper.writeValueAsString(resp),
        )
    }
}
