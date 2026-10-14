package com.scoophub.hotdeal

import com.scoophub.external.llm.LlmClient
import com.scoophub.global.config.ScoophubProperties
import com.scoophub.hotdeal.service.HotdealLlmJudge
import com.scoophub.hotdeal.vo.HotdealJudgment
import com.scoophub.hotdeal.vo.JudgmentCandidate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class HotdealLlmJudgeTest {
    private val llm = mockk<LlmClient>()
    private val mapper = JsonMapper.builder().build()

    private fun judge(apiUrl: String = "https://llm.test") = HotdealLlmJudge(
        llm,
        mockk<ScoophubProperties> { every { this@mockk.llm } returns ScoophubProperties.Llm(apiUrl, "", "test-model") },
        mapper,
    )

    private fun candidate(id: Long, title: String, popular: Boolean = false, matched: List<String> = listOf("램")) =
        JudgmentCandidate(id, title, "PPOMPPU", popular, if (popular) emptyList() else matched, "hash-$id")

    @Test
    fun `제외 키워드가 없으면 인기 항목은 LLM 없이 통과시킨다`() {
        // when
        val result = judge().judge(listOf(candidate(1, "아무 제품", popular = true)), emptyList())

        // then
        assertThat(result.getValue(1).judgedOut).isFalse()
        assertThat(result.getValue(1).status).isEqualTo(HotdealJudgment.STATUS_RULE)
        verify(exactly = 0) { llm.chat(any(), any()) }
    }

    @Test
    fun `LLM 이 관련 없다고 하면 숨김 대상으로 판정한다`() {
        // given
        every { llm.chat(any(), any()) } returns
            """결과: [{"id": 1, "relevant": false, "excluded": false, "reason": "램프는 메모리가 아님"}]"""

        // when
        val result = judge().judge(listOf(candidate(1, "LED 램프 특가")), emptyList())

        // then
        val judgment = result.getValue(1)
        assertThat(judgment.judgedOut).isTrue()
        assertThat(judgment.reason).isEqualTo("램프는 메모리가 아님")
        assertThat(judgment.status).isEqualTo(HotdealJudgment.STATUS_LLM)
        assertThat(judgment.inputHash).isEqualTo("hash-1")
    }

    @Test
    fun `인기 항목은 LLM 이 관련 없다고 해도 관련성은 통과시키고 제외만 본다`() {
        // given
        every { llm.chat(any(), any()) } returns
            """[{"id": 1, "relevant": false, "excluded": true, "reason": "중고"}]"""

        // when
        val result = judge().judge(listOf(candidate(1, "중고 노트북", popular = true)), listOf("중고"))

        // then
        assertThat(result.getValue(1).relevant).isTrue()
        assertThat(result.getValue(1).excluded).isTrue()
    }

    @Test
    fun `LLM 이 실패하면 관련성은 통과, 제외는 키워드 매칭으로 대신한다`() {
        // given
        every { llm.chat(any(), any()) } throws IllegalStateException("timeout")

        // when
        val result = judge().judge(
            listOf(candidate(1, "중고 램 16GB"), candidate(2, "새 램 16GB")),
            listOf("중고"),
        )

        // then
        assertThat(result.getValue(1).excluded).isTrue()
        assertThat(result.getValue(1).reason).isEqualTo("제외 키워드(중고)")
        assertThat(result.getValue(2).judgedOut).isFalse()
        assertThat(result.values.map { it.status }).containsOnly(HotdealJudgment.STATUS_FALLBACK)
    }

    @Test
    fun `LLM 응답에서 빠진 항목은 대체 판정한다`() {
        // given
        every { llm.chat(any(), any()) } returns """[{"id": 1, "relevant": true, "excluded": false, "reason": "ok"}]"""

        // when
        val result = judge().judge(listOf(candidate(1, "램 16GB"), candidate(2, "램 32GB")), emptyList())

        // then
        assertThat(result.getValue(1).status).isEqualTo(HotdealJudgment.STATUS_LLM)
        assertThat(result.getValue(2).status).isEqualTo(HotdealJudgment.STATUS_FALLBACK)
    }

    @Test
    fun `LLM 주소가 없으면 호출하지 않고 규칙으로 판정한다`() {
        // when
        val result = judge(apiUrl = "").judge(listOf(candidate(1, "중고 램")), listOf("중고"))

        // then
        assertThat(result.getValue(1).excluded).isTrue()
        assertThat(result.getValue(1).status).isEqualTo(HotdealJudgment.STATUS_RULE)
        verify(exactly = 0) { llm.chat(any(), any()) }
    }
}
