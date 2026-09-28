package com.scoophub.global.crawl

import com.scoophub.TestcontainersConfiguration
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.global.crawl.entity.CrawlLogEntity
import com.scoophub.global.crawl.enums.CrawlStatusEnum
import com.scoophub.global.crawl.repository.CrawlLogRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents

/** legacy tests/test_base_crawler.py 포팅 + 수동 트리거 응답 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class CrawlRunnerTest @Autowired constructor(
    private val runner: CrawlRunner,
    private val crawlLogRepository: CrawlLogRepository,
) {
    @Autowired
    private lateinit var events: ApplicationEvents

    private fun crawler(name: String, detail: String = "", fetch: () -> CrawlResult) = object : Crawler {
        override val name = name
        override val detail = detail
        override fun fetch() = fetch()
    }

    private fun onlyLog(): CrawlLogEntity = crawlLogRepository.findAll().single()

    @BeforeEach
    fun clean() = crawlLogRepository.deleteAllInBatch()

    @Test
    fun `성공 - success 로그와 완료 이벤트`() {
        val result = runner.run(crawler("test_ok", "d1") { CrawlResult(itemsFetched = 5, itemsNew = 3) })

        assertThat(result?.itemsFetched).isEqualTo(5)
        assertThat(result?.itemsNew).isEqualTo(3)
        with(onlyLog()) {
            assertThat(status).isEqualTo(CrawlStatusEnum.SUCCESS)
            assertThat(crawlerDetail).isEqualTo("d1")
            assertThat(itemsFetched).isEqualTo(5)
            assertThat(errorMessage).isNull()
            assertThat(finishedAt!!).isAfterOrEqualTo(startedAt)
        }
        assertThat(events.stream(CrawlCompletedEvent::class.java).toList())
            .isEqualTo(listOf(CrawlCompletedEvent("test_ok", "d1", result!!)))
    }

    @Test
    fun `일부 에러 - partial, 에러 메시지는 세미콜론 결합`() {
        runner.run(crawler("test_partial") { CrawlResult(itemsFetched = 2, errors = listOf("a", "b")) })

        with(onlyLog()) {
            assertThat(status).isEqualTo(CrawlStatusEnum.PARTIAL)
            assertThat(errorMessage).isEqualTo("a; b")
        }
        assertThat(events.stream(CrawlCompletedEvent::class.java).count()).isEqualTo(1)
    }

    @Test
    fun `fetch 예외 - error 로그, 이벤트 없음, null 반환`() {
        val result = runner.run(crawler("test_error") { error("boom") })

        assertThat(result).isNull()
        with(onlyLog()) {
            assertThat(status).isEqualTo(CrawlStatusEnum.ERROR)
            assertThat(errorMessage).isEqualTo("boom")
            assertThat(crawlerDetail).isEqualTo("")
        }
        assertThat(events.stream(CrawlCompletedEvent::class.java).count()).isEqualTo(0)
    }

    @Test
    fun `news 크롤러는 완료 이벤트 없음 - 요약 후 자체 발신`() {
        runner.run(crawler("news") { CrawlResult(itemsNew = 1) })

        assertThat(events.stream(CrawlCompletedEvent::class.java).count()).isEqualTo(0)
    }

    @Test
    fun `수동 트리거 - 성공 응답`() {
        val resp = runner.trigger(
            crawler("hacker_news") {
                CrawlResult(itemsFetched = 30, itemsNew = 4)
            },
            "Hacker News",
        )

        assertThat(resp.success).isTrue()
        assertThat(resp.data).isEqualTo(CrawlTriggerData("hacker_news", "", 30, 4, null))
    }

    @Test
    fun `수동 트리거 실패 - success=false 와 crawl_failed 코드`() {
        val resp = runner.trigger(crawler("hacker_news") { error("boom") }, "Hacker News")

        assertThat(resp.success).isFalse()
        assertThat(resp.error?.code).isEqualTo("crawl_failed")
        assertThat(resp.error?.message).isEqualTo("Hacker News 크롤 실패")
    }
}
