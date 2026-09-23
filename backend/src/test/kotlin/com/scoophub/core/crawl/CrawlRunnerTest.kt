package com.scoophub.core.crawl

import com.scoophub.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun onlyLog() = crawlLogRepository.findAll().single()

    @BeforeEach
    fun clean() = crawlLogRepository.deleteAllInBatch()

    @Test
    fun `성공 - success 로그와 완료 이벤트`() {
        val result = runner.run(crawler("test_ok", "d1") { CrawlResult(itemsFetched = 5, itemsNew = 3) })

        assertEquals(5, result?.itemsFetched)
        assertEquals(3, result?.itemsNew)
        with(onlyLog()) {
            assertEquals("success", status)
            assertEquals("d1", crawlerDetail)
            assertEquals(5, itemsFetched)
            assertNull(errorMessage)
            assertTrue(finishedAt!! >= startedAt)
        }
        assertEquals(
            listOf(CrawlCompletedEvent("test_ok", "d1", result!!)),
            events.stream(CrawlCompletedEvent::class.java).toList(),
        )
    }

    @Test
    fun `일부 에러 - partial, 에러 메시지는 세미콜론 결합`() {
        runner.run(crawler("test_partial") { CrawlResult(itemsFetched = 2, errors = listOf("a", "b")) })

        with(onlyLog()) {
            assertEquals("partial", status)
            assertEquals("a; b", errorMessage)
        }
    }

    @Test
    fun `예외는 전파되지 않고 error 로그, 이벤트 없음`() {
        val result = runner.run(crawler("test_fail") { throw RuntimeException("boom") })

        assertNull(result)
        with(onlyLog()) {
            assertEquals("error", status)
            assertTrue("boom" in errorMessage!!)
            assertEquals("", crawlerDetail)
        }
        assertEquals(0, events.stream(CrawlCompletedEvent::class.java).count())
    }

    @Test
    fun `news 는 완료 이벤트를 발행하지 않는다 - 요약 후 자체 발신`() {
        runner.run(crawler("news") { CrawlResult(itemsNew = 1) })

        assertEquals(0, events.stream(CrawlCompletedEvent::class.java).count())
    }

    @Test
    fun `수동 트리거 성공 응답 - errors 비면 null`() {
        val resp = runner.trigger(crawler("hacker_news") { CrawlResult(itemsFetched = 30, itemsNew = 4) }, "Hacker News")

        assertTrue(resp.success)
        assertEquals(CrawlTriggerData("hacker_news", "", 30, 4, null), resp.data)
    }

    @Test
    fun `수동 트리거 실패 응답 - success false, crawl_failed`() {
        val resp = runner.trigger(crawler("hacker_news") { throw RuntimeException("boom") }, "Hacker News")

        assertEquals(false, resp.success)
        assertEquals("crawl_failed", resp.error?.code)
        assertEquals("Hacker News 크롤 실패", resp.error?.message)
    }
}
