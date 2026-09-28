package com.scoophub.stock

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** legacy `stock/crawler.py` _parse_float 포팅 — SigmaCrawler companion */
class SigmaCrawlerTest {

    @Test
    fun `parseFloat 는 쉼표와 퍼센트와 달러기호를 제거한 뒤 파싱한다`() {
        // given & when & then — usstocksigma.com 테이블 셀 형식
        assertThat(SigmaCrawler.parseFloat("1,234.56")).isEqualTo(1234.56)
        assertThat(SigmaCrawler.parseFloat("12.5%")).isEqualTo(12.5)
        assertThat(SigmaCrawler.parseFloat("$98.75")).isEqualTo(98.75)
        assertThat(SigmaCrawler.parseFloat(" 105.30 ")).isEqualTo(105.30)
    }

    @Test
    fun `parseFloat 는 파싱할 수 없는 입력이면 0을 반환한다`() {
        // given & when & then — 빈 셀/비고 셀 방어
        assertThat(SigmaCrawler.parseFloat("N/A")).isEqualTo(0.0)
        assertThat(SigmaCrawler.parseFloat("")).isEqualTo(0.0)
        assertThat(SigmaCrawler.parseFloat("--")).isEqualTo(0.0)
    }
}
