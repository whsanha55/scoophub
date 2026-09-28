package com.scoophub.global.schedule

import com.scoophub.global.config.ScoophubProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * legacy `AsyncIOScheduler` + `BaseScheduler.register_job` 대응.
 * 잡별 `ScheduledFuture` 를 보관해 런타임 재스케줄(system 스케줄 PATCH)을 지원한다.
 *
 * 동시 실행 방지: cron(Trigger) 잡은 실행 완료 후 다음 주기를 예약하고,
 * fixed-delay 잡도 이전 실행 완료 후 주기를 잰다 — APScheduler `max_instances=1`,
 * `coalesce` 와 같은 효과로 잡마다 중복 실행이 일어나지 않는다.
 */
@Component
class CrawlScheduler(
    private val taskScheduler: TaskScheduler,
    private val resolver: ScheduleResolver,
    properties: ScoophubProperties,
    private val clock: Clock,
) {
    private val enabled = properties.enableScheduler

    /** jobId → 등록 상태. future == null 은 paused (APScheduler `pause_job` 대응) */
    private class Entry(val job: ScheduledJob) {
        @Volatile
        var future: ScheduledFuture<*>? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 기동 시 전체 잡 등록. ENABLE_SCHEDULER=false 면 두지 않는다 */
    fun start(jobs: List<ScheduledJob>) {
        if (!enabled) {
            log.info { "Scheduler disabled (ENABLE_SCHEDULER=false)" }
            return
        }
        log.info { "Scheduler started" }
        jobs.forEach { register(it) }
    }

    /** crawl_schedule/crawl_config 을 읽어 (재)등록 — legacy `add_job(replace_existing=True)` + pause */
    fun register(job: ScheduledJob) {
        val resolved = resolver.resolveTrigger(job.crawler, job.jobId)
        val params = resolver.resolveParams(job.crawler)
        val entry = entries.computeIfAbsent(job.jobId) { Entry(job) }
        entry.future?.cancel(false)
        entry.future = if (resolved.enabled) schedule(job, params, resolved.trigger) else null
        log.info { "Scheduled job '${job.jobId}' (crawler=${job.crawler}, enabled=${resolved.enabled})" }
    }

    /**
     * 런타임 재스케줄 — system 스케줄 PATCH 가 DB 갱신 후 호출.
     * 주기 변경은 재등록으로, enabled 토글은 schedule/cancel 로 반영된다.
     */
    fun apply(crawler: String, jobId: String) {
        val entry = entries[jobId]
            ?: throw IllegalStateException("Job not registered in scheduler: $jobId")
        register(entry.job)
    }

    /** boot 시 등록된 잡인지 (paused 포함) */
    fun isRegistered(jobId: String): Boolean = entries.containsKey(jobId)

    /**
     * 다음 실행 예정 시각. paused/미등록이면 null.
     * ScheduledFuture 의 남은 지연으로 계산한다 (표시용 근사값).
     */
    fun nextRunTime(jobId: String): Instant? = entries[jobId]?.future
        ?.takeIf { !it.isCancelled }
        ?.let { Instant.now(clock).plusMillis(it.getDelay(TimeUnit.MILLISECONDS)) }

    private fun schedule(job: ScheduledJob, params: JsonNode, trigger: ScheduleTrigger): ScheduledFuture<*>? =
        when (trigger) {
            is ScheduleTrigger.Cron ->
                taskScheduler.schedule({ job.run(params) }, CronTriggers.of(trigger.expressions))

            is ScheduleTrigger.Interval -> {
                val period = Duration.ofMinutes(trigger.minutes.toLong())
                // 첫 실행은 interval 후 — IntervalTrigger 기본 동작과 동일
                taskScheduler.scheduleWithFixedDelay(
                    { job.run(params) },
                    clock.instant().plus(period),
                    period,
                )
            }
        }
}
