package com.scoophub.hotdeal

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.zod.ZodClient
import com.scoophub.external.zod.ZodDeal
import com.scoophub.global.crawl.CrawlRunner
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.IOException
import java.time.Instant

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ZodCrawlerTest @Autowired constructor(
    private val crawlRunner: CrawlRunner,
    private val crawler: ZodCrawler,
    private val jdbcClient: JdbcClient,
) {
    @MockkBean
    private lateinit var zodClient: ZodClient

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM hotdeal_item").update()
    }

    private fun deal(sourceKey: String, votes: Long) = ZodDeal(
        sourceKey = sourceKey,
        title = "테스트 딜 $sourceKey",
        url = "https://zod.kr/deal/$sourceKey",
        mall = "지마켓",
        price = 10000L,
        shippingFee = "무료",
        itemAt = Instant.parse("2026-10-13T05:50:00Z"),
        votes = votes,
    )

    private fun votesOf(sourceKey: String): Long = jdbcClient.sql(
        "SELECT (extra->>'votes')::bigint FROM hotdeal_item WHERE source = 'ZOD' AND source_key = :key",
    ).param("key", sourceKey).query(Long::class.java).single()

    @Test
    fun `신규 딜을 수집하면 hotdeal_item 에 저장한다`() {
        // given
        every { zodClient.deals() } returns listOf(deal("100", 3), deal("101", 0))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.itemsFetched).isEqualTo(2)
        assertThat(result.itemsNew).isEqualTo(2)
        assertThat(votesOf("100")).isEqualTo(3L)
    }

    @Test
    fun `같은 딜을 재수집하면 행 하나로 유지되고 메타데이터만 갱신한다`() {
        // given
        every { zodClient.deals() } returns listOf(deal("100", 1))
        crawlRunner.run(crawler)
        every { zodClient.deals() } returns listOf(deal("100", 7))

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.itemsNew).isZero()
        val count = jdbcClient.sql("SELECT COUNT(*) FROM hotdeal_item").query(Long::class.java).single()
        assertThat(count).isEqualTo(1L)
        assertThat(votesOf("100")).isEqualTo(7L)
    }

    @Test
    fun `파싱 결과가 0건이면 오류를 남긴다`() {
        // given
        every { zodClient.deals() } returns emptyList()

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.errors).hasSize(1)
    }

    @Test
    fun `요청이 실패하면 오류를 남기고 저장하지 않는다`() {
        // given
        every { zodClient.deals() } throws IOException("HTTP error fetching URL. Status=403")

        // when
        val result = crawlRunner.run(crawler)

        // then
        assertThat(result!!.errors).containsExactly("HTTP error fetching URL. Status=403")
        val count = jdbcClient.sql("SELECT COUNT(*) FROM hotdeal_item").query(Long::class.java).single()
        assertThat(count).isZero()
    }
}
