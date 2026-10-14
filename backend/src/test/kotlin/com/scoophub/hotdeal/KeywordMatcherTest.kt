package com.scoophub.hotdeal

import com.scoophub.hotdeal.service.KeywordMatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KeywordMatcherTest {

    @Test
    fun `한글 키워드는 부분 문자열로 매칭한다`() {
        // given
        val text = "WD SN850X 2TB NVMe SSD 저렴한 가격"

        // when / then
        assertThat(KeywordMatcher.matches(text, "SSD")).isTrue()
        assertThat(KeywordMatcher.matches(text, "sn850x")).isTrue()
        assertThat(KeywordMatcher.matches(text, "가격")).isTrue()
    }

    @Test
    fun `영문 키워드는 토큰 경계로만 매칭한다`() {
        // given
        val text = "삼성 SSD 990PRO 출시"

        // when / then
        assertThat(KeywordMatcher.matches(text, "SSD")).isTrue()
        assertThat(KeywordMatcher.matches(text, "ssd")).isTrue()
        assertThat(KeywordMatcher.matches(text, "SD")).isFalse()
        assertThat(KeywordMatcher.matches("SSDS 후속작", "SSD")).isFalse()
    }

    @Test
    fun `숫자를 포함한 키워드도 경계 매칭으로 오탐을 막는다`() {
        // given
        val text = "RTX 5090 그래픽카드 특가"

        // when / then
        assertThat(KeywordMatcher.matches(text, "RTX 5090")).isTrue()
        assertThat(KeywordMatcher.matches(text, "5090")).isTrue()
        assertThat(KeywordMatcher.matches(text, "50900")).isFalse()
    }

    @Test
    fun `빈 키워드는 매칭하지 않는다`() {
        // when / then
        assertThat(KeywordMatcher.matches("아무 텍스트", "")).isFalse()
    }
}
