package com.scoophub.system.service

import com.scoophub.global.schedule.CrawlScheduler
import com.scoophub.global.schedule.CronTriggers
import com.scoophub.global.schedule.repository.CrawlScheduleQueryRepository
import com.scoophub.global.schedule.repository.CrawlScheduleRepository
import com.scoophub.system.dto.ScheduleItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

private val log = KotlinLogging.logger {}

@Service
class SystemScheduleService(
    private val scheduleRepository: CrawlScheduleRepository,
    private val scheduleQueryRepository: CrawlScheduleQueryRepository,
    private val crawlScheduler: CrawlScheduler,
) {
    fun findAll(): List<ScheduleItem> = scheduleRepository.findAll()
        .sortedWith(compareBy({ it.crawler }, { it.jobId }))
        .map { attachRuntime(ScheduleItem.from(it)) }

    fun find(crawler: String, jobId: String): ScheduleItem {
        val row = scheduleRepository.findByCrawlerAndJobId(crawler, jobId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found")
        return attachRuntime(ScheduleItem.from(row))
    }

    /** 주기(schedules/schedule_minutes) 또는 활성화(enabled) 변경. 변경 즉시 런타임에 반영 */
    fun update(
        crawler: String,
        jobId: String,
        schedules: List<String>?,
        scheduleMinutes: Int?,
        enabled: Boolean?,
    ): ScheduleItem {
        val existing = scheduleRepository.findByCrawlerAndJobId(crawler, jobId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found")
        val scheduleType = existing.scheduleType

        if (schedules != null) {
            if (scheduleType != "cron") {
                throw ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "schedules can only be set for schedule_type='cron'",
                )
            }
            if (schedules.isEmpty()) {
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "schedules must not be empty")
            }
            // 각 cron expr 검증 — 등록과 동일 KST tz (CronTrigger 생성자가 파싱 실패 시 예외)
            for (expr in schedules) {
                try {
                    CronTrigger("0 $expr", CronTriggers.SEOUL)
                } catch (e: IllegalArgumentException) {
                    throw ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid cron expression '$expr': ${e.message}",
                    )
                }
            }
        }
        if (scheduleMinutes != null && scheduleType != "interval") {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schedule_minutes can only be set for schedule_type='interval'",
            )
        }
        if (!scheduleQueryRepository.update(crawler, jobId, schedules, scheduleMinutes, enabled)) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "no updatable fields provided")
        }

        // 런타임 반영 — 미등록 잡이면 500 (legacy 동일)
        try {
            crawlScheduler.apply(crawler, jobId)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message)
        }

        val row = requireNotNull(scheduleRepository.findByCrawlerAndJobId(crawler, jobId))
        log.info { "Updated schedule (crawler=$crawler, job_id=$jobId, enabled=${row.enabled})" }
        return attachRuntime(ScheduleItem.from(row))
    }

    /** APScheduler next_run_time/paused 조인 대응 — nextRunTime(KST isoformat), paused */
    private fun attachRuntime(item: ScheduleItem): ScheduleItem {
        if (!crawlScheduler.isRegistered(item.jobId)) {
            return item // 미등록 — next_run_time=null, paused=null
        }
        val next = crawlScheduler.nextRunTime(item.jobId)
        return item.copy(
            nextRunTime = next?.atZone(CronTriggers.SEOUL)?.toString(),
            paused = next == null,
        )
    }
}
