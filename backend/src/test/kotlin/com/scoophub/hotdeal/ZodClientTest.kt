package com.scoophub.hotdeal

import com.scoophub.external.zod.ZodClient
import com.scoophub.scoophubProperties
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** zod.kr/deal 마크업 파싱 검증 — 저장된 HTML fixture 기반 회귀 테스트 */
class ZodClientTest {
    private val client = ZodClient(scoophubProperties())

    private fun parse(html: String) = client.parse(Jsoup.parse(html))

    @Test
    fun `저장된 목록 HTML에서 20건의 핫딜을 파싱한다`() {
        // when
        val deals = parse(FIXTURE)

        // then
        assertThat(deals).hasSize(20)
    }

    @Test
    fun `첫 항목의 제목 몰 가격 배송비 추천수 게시시각을 파싱한다`() {
        // when
        val deal = parse(FIXTURE).first()

        // then
        assertThat(deal.sourceKey).isEqualTo("8887617")
        assertThat(deal.title).isEqualTo("에이서 프레데터 GM9000 2TB")
        assertThat(deal.url).isEqualTo("https://zod.kr/deal/8887617")
        assertThat(deal.mall).isEqualTo("지마켓")
        assertThat(deal.price).isEqualTo(543720L)
        assertThat(deal.shippingFee).isEqualTo("무료")
        assertThat(deal.votes).isEqualTo(1L)
        assertThat(deal.itemAt).isEqualTo(
            LocalDateTime.parse("2026-10-13T15:20:17").atZone(SEOUL).toInstant(),
        )
    }

    @Test
    fun `0원 공짜딜은 가격 0으로 파싱한다`() {
        // given — fixture 인기글 영역은 파싱 대상이 아니므로 별도 HTML로 검증한다
        val html = """
            <a class="app-list-link" href="/deal/1">
              <div class="app-list-body">
                <div class="app-list-title"><span class="app-list-title-item">무료 딜</span></div>
                <dl class="app-list-meta zod-board--deal-meta">
                  <dt>홈페이지/장소</dt><dd><strong>쿠팡</strong></dd>
                  <dt>가격</dt><dd>가격: <strong>0원</strong></dd>
                  <dt>배송비</dt><dd>배송비: <strong>무료</strong></dd>
                </dl>
                <dl class="app-list-meta">
                  <dd title="2026-10-13 10:00:00"><i class="iconoir-calendar"></i>3시간 전</dd>
                  <dd class="app-list__voted-count"><i class="iconoir-heart"></i>12</dd>
                </dl>
              </div>
            </a>
        """.trimIndent()

        // when
        val deal = parse(html).single()

        // then
        assertThat(deal.price).isZero()
        assertThat(deal.votes).isEqualTo(12L)
        assertThat(deal.title).isEqualTo("무료 딜")
    }

    @Test
    fun `제목이나 게시 시각이 없는 항목은 건너뛴다`() {
        // given
        val html = """
            <a class="app-list-link" href="/deal/2">
              <div class="app-list-body">
                <div class="app-list-title"><span class="app-list-title-item">시각 없음</span></div>
              </div>
            </a>
        """.trimIndent()

        // when
        val deals = parse(html)

        // then
        assertThat(deals).isEmpty()
    }

    @Test
    fun `핫딜 목록이 없으면 빈 목록을 반환한다`() {
        // when
        val deals = parse("<html><body>Access Denied</body></html>")

        // then
        assertThat(deals).isEmpty()
    }

    companion object {
        private val FIXTURE = requireNotNull(ZodClientTest::class.java.getResource("/fixtures/zod/deal.html"))
            .readText()

        // zod 게시판 시각은 KST로 표기된다.
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
