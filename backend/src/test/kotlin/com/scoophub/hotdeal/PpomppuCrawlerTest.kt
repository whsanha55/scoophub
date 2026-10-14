package com.scoophub.hotdeal

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.ppomppu.PpomppuClient
import com.scoophub.external.ppomppu.PpomppuDeal
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.service.HotdealItemService
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PpomppuCrawlerTest @Autowired constructor(
    private val crawlRunner: CrawlRunner,
    private val crawler: PpomppuCrawler,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
    itemService: HotdealItemService,
    profileRepository: HotdealInterestProfileRepository,
) {
    private val fixtures = HotdealTestFixtures(jdbcClient, itemService, profileRepository)

    @MockkBean
    private lateinit var ppomppuClient: PpomppuClient

    @BeforeEach
    fun clean() = fixtures.clean()

    @Test
    fun `수집한 딜을 PPOMPPU 소스로 저장하고 콜드스타트 인기 노출까지 만든다`() {
        // given
        every { ppomppuClient.deals() } returns listOf(
            PpomppuDeal(
                sourceKey = "739984",
                title = "하늘보리 500ml 40입",
                url = "https://www.ppomppu.co.kr/zboard/view.php?id=ppomppu&no=739984",
                mall = "G마켓",
                price = 20020L,
                shippingFee = "무배",
                itemAt = clock.instant().minusSeconds(300),
                votes = 3,
            ),
        )

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.itemsNew).isEqualTo(1)
        assertThat(result.errors).isEmpty()
        val mall = jdbcClient.sql(
            "SELECT extra->>'mall' FROM hotdeal_item WHERE source = 'PPOMPPU' AND source_key = '739984'",
        ).query(String::class.java).single()
        assertThat(mall).isEqualTo("G마켓")
        val feedCount = jdbcClient.sql("SELECT COUNT(*) FROM hotdeal_feed_item").query(Long::class.java).single()
        assertThat(feedCount).isEqualTo(1L)
    }
}
