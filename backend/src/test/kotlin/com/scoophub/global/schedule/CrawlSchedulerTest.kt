package com.scoophub.global.schedule

import com.scoophub.global.config.ScoophubProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.support.CronTrigger
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** legacy tests/test_scheduler.py 의 register/pause 동작 포팅 — TaskScheduler/resolver 를 mock */
class CrawlSchedulerTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val params: JsonNode = jsonMapper.readTree("""{"max_repos": 25}""")
    private val fixedNow = Instant.parse("2027-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val taskScheduler = mockk<TaskScheduler>()
    private val resolver = mockk<ScheduleResolver>()
    private val future = mockk<ScheduledFuture<*>>(relaxed = true)

    private class RecordingJob(override val crawler: String, override val jobId: String) : ScheduledJob {
        val received = mutableListOf<JsonNode>()

        override fun run(params: JsonNode) {
            received.add(params)
        }
    }

    private fun scheduler(enableScheduler: Boolean = true) =
        CrawlScheduler(taskScheduler, resolver, properties(enableScheduler), clock)

    private fun properties(enableScheduler: Boolean) = ScoophubProperties(
        enableScheduler = enableScheduler,
        corsOrigins = emptyList(),
        llm = ScoophubProperties.Llm(apiUrl = "", apiKey = "", model = ""),
        auth = ScoophubProperties.Auth(
            allowedEmails = emptySet(),
            superEmails = emptySet(),
            googleClientId = "",
            googleClientSecret = "",
            jwtSecret = "",
            jwtExpireHours = 24,
            bypass = false,
            redirectUrl = "",
            oauthRedirectUri = "",
        ),
        telegram = ScoophubProperties.Telegram(botToken = "", defaultChatId = ""),
        youtubeApiKey = "",
    )

    private fun stubResolve(trigger: ScheduleTrigger, enabled: Boolean = true, crawler: String = "weather") {
        every { resolver.resolveTrigger(crawler, any()) } returns ScheduleResolver.Resolved(trigger, enabled)
        every { resolver.resolveParams(crawler) } returns params
    }

    @Test
    fun `start 는 ENABLE_SCHEDULER false 면 잡을 두지 않는다`() {
        // given
        val job = RecordingJob("weather", "weather_crawler")

        // when
        scheduler(enableScheduler = false).start(listOf(job))

        // then
        verify(exactly = 0) { taskScheduler.schedule(any(), any<Trigger>()) }
        verify(exactly = 0) { taskScheduler.scheduleWithFixedDelay(any(), any<Instant>(), any()) }
    }

    @Test
    fun `interval 잡은 첫 실행 interval 후 fixed-delay 로 등록한다`() {
        // given
        val job = RecordingJob("weather", "weather_crawler")
        stubResolve(ScheduleTrigger.Interval(30))
        every { taskScheduler.scheduleWithFixedDelay(any(), any<Instant>(), any()) } returns future

        // when
        scheduler().start(listOf(job))

        // then
        val startSlot = mutableListOf<Instant>()
        val periodSlot = mutableListOf<Duration>()
        verify {
            taskScheduler.scheduleWithFixedDelay(any(), capture(startSlot), capture(periodSlot))
        }
        assertThat(startSlot.single()).isEqualTo(fixedNow.plus(Duration.ofMinutes(30)))
        assertThat(periodSlot.single()).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `cron 잡은 KST 6필드 CronTrigger 로 등록한다`() {
        // given
        val job = RecordingJob("github_trending", "github_trending_crawler")
        stubResolve(ScheduleTrigger.Cron(listOf("0 9 * * *")), crawler = "github_trending")
        every { taskScheduler.schedule(any(), any<Trigger>()) } returns future

        // when
        scheduler().start(listOf(job))

        // then
        val triggerSlot = mutableListOf<Trigger>()
        verify { taskScheduler.schedule(any(), capture(triggerSlot)) }
        val trigger = triggerSlot.single()
        assertThat(trigger).isInstanceOf(CronTrigger::class.java)
        assertThat((trigger as CronTrigger).expression).isEqualTo("0 0 9 * * *")
    }

    @Test
    fun `enabled false 잡은 paused — 등록 표시만 되고 예정 시각은 없다`() {
        // given
        val job = RecordingJob("weather", "weather_crawler")
        stubResolve(ScheduleTrigger.Interval(30), enabled = false)
        val crawlScheduler = scheduler()

        // when
        crawlScheduler.start(listOf(job))

        // then
        assertThat(crawlScheduler.isRegistered("weather_crawler")).isTrue()
        assertThat(crawlScheduler.nextRunTime("weather_crawler")).isNull()
        verify(exactly = 0) { taskScheduler.scheduleWithFixedDelay(any(), any<Instant>(), any()) }
    }

    @Test
    fun `재등록하면 기존 future 를 취소하고 교체한다`() {
        // given
        val job = RecordingJob("weather", "weather_crawler")
        stubResolve(ScheduleTrigger.Interval(30))
        val oldFuture = mockk<ScheduledFuture<*>>(relaxed = true)
        every { taskScheduler.scheduleWithFixedDelay(any(), any<Instant>(), any()) } returnsMany
            listOf(oldFuture, future)
        val crawlScheduler = scheduler()

        // when
        crawlScheduler.start(listOf(job))
        crawlScheduler.register(job)

        // then
        verify(exactly = 1) { oldFuture.cancel(false) }
        assertThat(crawlScheduler.isRegistered("weather_crawler")).isTrue()
    }

    @Test
    fun `apply 는 DB 갱신을 런타임에 반영한다`() {
        // given — 등록된 잡을 enabled=false 로 토글
        val job = RecordingJob("weather", "weather_crawler")
        stubResolve(ScheduleTrigger.Interval(30))
        every { taskScheduler.scheduleWithFixedDelay(any(), any<Instant>(), any()) } returns future
        val crawlScheduler = scheduler()
        crawlScheduler.start(listOf(job))
        stubResolve(ScheduleTrigger.Interval(30), enabled = false)

        // when
        crawlScheduler.apply("weather", "weather_crawler")

        // then
        verify(exactly = 1) { future.cancel(false) }
        assertThat(crawlScheduler.nextRunTime("weather_crawler")).isNull()
        assertThat(crawlScheduler.isRegistered("weather_crawler")).isTrue()
    }

    @Test
    fun `nextRunTime 는 남은 지연을 더한 시각`() {
        // given
        val job = RecordingJob("weather", "weather_crawler")
        stubResolve(ScheduleTrigger.Interval(30))
        every { taskScheduler.scheduleWithFixedDelay(any(), any<Instant>(), any()) } returns future
        every { future.isCancelled } returns false
        every { future.getDelay(TimeUnit.MILLISECONDS) } returns 90_000L
        val crawlScheduler = scheduler()

        // when
        crawlScheduler.start(listOf(job))
        val next = crawlScheduler.nextRunTime("weather_crawler")

        // then
        assertThat(next).isEqualTo(fixedNow.plusSeconds(90))
    }
}
