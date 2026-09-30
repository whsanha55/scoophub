package com.scoophub.external.alpaca

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class AlpacaNewsDecoderTest {
    @Test
    fun `이스케이프된 headline과 summary를 원문으로 복원한다`() {
        // given
        val node = JsonMapper().readTree(
            """
            {"T":"n","id":1,"headline":"Zuckerberg Labels It &#39;Positive&#39; &amp; More",
            "summary":"S&amp;P 500","symbols":[],
            "created_at":"2026-09-30T00:00:00Z","updated_at":"2026-09-30T00:00:01Z"}
            """.trimIndent(),
        )
        // when
        val row = AlpacaNewsDecoder().decode(node)
        // then
        assertThat(row.headline).isEqualTo("Zuckerberg Labels It 'Positive' & More")
        assertThat(row.summary).isEqualTo("S&P 500")
    }
}
