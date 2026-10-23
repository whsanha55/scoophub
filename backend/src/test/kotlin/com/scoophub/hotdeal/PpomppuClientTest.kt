package com.scoophub.hotdeal

import com.scoophub.external.ppomppu.PpomppuClient
import com.scoophub.scoophubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

/** 뽐뿌 RSS 파싱 — 저장된 RSS fixture 기반 회귀 테스트 */
class PpomppuClientTest {
    private val client = PpomppuClient(RestClient.builder(), scoophubProperties())

    private fun item(title: String, hits: String = "[0|10|3|0]") = """
        <rss version="2.0"><channel><title>뽐뿌</title>
        <item>
          <title><![CDATA[$title]]></title>
          <link>http://www.ppomppu.co.kr/zboard/view.php?id=ppomppu&amp;no=1</link>
          <pubDate>Tue, 13 Oct 2026 23:57:55 GMT</pubDate>
          <hits>$hits</hits>
        </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `저장된 RSS 에서 15건을 파싱한다`() {
        // when
        val deals = client.parse(FIXTURE)

        // then
        assertThat(deals).hasSize(15)
    }

    @Test
    fun `첫 항목의 몰 상품명 가격 배송 게시시각 링크를 파싱한다`() {
        // when
        val deal = client.parse(FIXTURE).first()

        // then
        assertThat(deal.sourceKey).isEqualTo("739984")
        assertThat(deal.mall).isEqualTo("G마켓")
        assertThat(deal.title).isEqualTo("20입+20입 하늘보리/옥수수수염차 외 9종 500ml 골라담기")
        assertThat(deal.price).isEqualTo(20020L)
        assertThat(deal.shippingFee).isEqualTo("무배")
        assertThat(deal.itemAt).isEqualTo(Instant.parse("2026-10-13T23:57:55Z"))
        assertThat(deal.url).isEqualTo("https://www.ppomppu.co.kr/zboard/view.php?id=ppomppu&no=739984")
    }

    @Test
    fun `hits 의 세 번째 값을 추천수로 쓴다`() {
        // when
        val deal = client.parse(item("[몰] 상품 (1,000원/무료)", hits = " [2|2019|7|0]")).single()

        // then
        assertThat(deal.votes).isEqualTo(7L)
    }

    @Test
    fun `원 표기 없는 가격과 쉼표로 구분한 배송을 파싱한다`() {
        // when
        val noWon = client.parse(item("[토스] 화장지 30롤 (16,600/무료)")).single()
        val comma = client.parse(item("[이랜드몰] 스니커즈 (20,180원,무료배송)")).single()

        // then
        assertThat(noWon.price).isEqualTo(16600L)
        assertThat(noWon.shippingFee).isEqualTo("무료")
        assertThat(comma.price).isEqualTo(20180L)
        assertThat(comma.shippingFee).isEqualTo("무료배송")
    }

    @Test
    fun `가격이 다양하면 가격은 비우고 배송만 남긴다`() {
        // when
        val deal = client.parse(item("[지마켓] 세트도서 (상품별다양/무료)")).single()

        // then
        assertThat(deal.price).isNull()
        assertThat(deal.shippingFee).isEqualTo("무료")
        assertThat(deal.title).isEqualTo("세트도서")
    }

    @Test
    fun `괄호가 없으면 몰만 분리하고 나머지를 상품명으로 둔다`() {
        // when
        val deal = client.parse(item("[G마켓] 광동 헛개차 1.5L 12입 13100원 무배")).single()

        // then
        assertThat(deal.mall).isEqualTo("G마켓")
        assertThat(deal.title).isEqualTo("광동 헛개차 1.5L 12입 13100원 무배")
        assertThat(deal.price).isNull()
    }

    @Test
    fun `몰 표기가 없으면 제목 전체를 상품명으로 둔다`() {
        // when
        val deal = client.parse(item("공지 같은 형식 없는 제목")).single()

        // then
        assertThat(deal.mall).isNull()
        assertThat(deal.title).isEqualTo("공지 같은 형식 없는 제목")
    }

    @Test
    fun `RSS 가 아니면 빈 목록을 반환한다`() {
        // when
        val deals = client.parse("<html><body>Access Denied</body></html>")

        // then
        assertThat(deals).isEmpty()
    }

    companion object {
        private val FIXTURE = requireNotNull(PpomppuClientTest::class.java.getResource("/fixtures/ppomppu/rss.xml"))
            .readText()
    }
}
