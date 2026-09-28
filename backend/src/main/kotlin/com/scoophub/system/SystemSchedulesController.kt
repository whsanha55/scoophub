package com.scoophub.system

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.schedule.CrawlScheduler
import com.scoophub.global.schedule.CronTriggers
import com.scoophub.global.schedule.repository.CrawlScheduleRepository
import com.scoophub.system.dto.ScheduleItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.support.CronTrigger
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `system/schedules_router.py` — crawl_schedule 관리 + 런타임 재스케줄 */
@RestController
class SystemSchedulesController(
    private val scheduleRepository: CrawlScheduleRepository,
    private val crawlScheduler: CrawlScheduler,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    data class ScheduleUpdateRequest(
        val schedules: List<String>? = null,
        val scheduleMinutes: Int? = null,
        val enabled: Boolean? = null,
    )

    @Tag(name = "Schedules")
    @Operation(summary = "전체 스케줄 조회")
    @GetMapping("/api/schedules")
    fun listSchedules(): ApiResponse<List<ScheduleItem>> {
        val items = scheduleRepository.findAll()
            .sortedWith(compareBy({ it.crawler }, { it.jobId }))
            .map { attachRuntime(ScheduleItem.from(it)) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size))
    }

    @Tag(name = "Schedules")
    @Operation(summary = "단일 스케줄 조회")
    @GetMapping("/api/schedules/{crawler}/{job_id}")
    fun getSchedule(@PathVariable crawler: String, @PathVariable("job_id") jobId: String): ApiResponse<ScheduleItem> {
        val row = scheduleRepository.findByCrawlerAndJobId(crawler, jobId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found")
        return ApiResponse.ok(attachRuntime(ScheduleItem.from(row)), ResponseMeta(clock.instant()))
    }

    /** 주기(schedules/schedule_minutes) 또는 활성화(enabled) 변경. 변경 즉시 런타임에 반영 */
    @Tag(name = "Schedules")
    @Operation(summary = "스케줄 수정")
    @SuperOnly
    @PatchMapping("/api/schedules/{crawler}/{job_id}")
    fun updateSchedule(
        @PathVariable crawler: String,
        @PathVariable("job_id") jobId: String,
        @RequestBody body: ScheduleUpdateRequest,
    ): ApiResponse<ScheduleItem> {
        val existing = scheduleRepository.findByCrawlerAndJobId(crawler, jobId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found")
        val scheduleType = existing.scheduleType

        val sets = mutableListOf<String>()
        if (body.schedules != null) {
            if (scheduleType != "cron") {
                throw ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "schedules can only be set for schedule_type='cron'",
                )
            }
            if (body.schedules.isEmpty()) {
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "schedules must not be empty")
            }
            // 각 cron expr 검증 — 등록과 동일 KST tz (CronTrigger 생성자가 파싱 실패 시 예외)
            for (expr in body.schedules) {
                try {
                    CronTrigger("0 $expr", CronTriggers.SEOUL)
                } catch (e: IllegalArgumentException) {
                    throw ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid cron expression '$expr': ${e.message}",
                    )
                }
            }
            sets += buildArraySql("schedules", body.schedules)
        }
        if (body.scheduleMinutes != null) {
            if (scheduleType != "interval") {
                throw ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "schedule_minutes can only be set for schedule_type='interval'",
                )
            }
            sets += "schedule_minutes = ${body.scheduleMinutes}"
        }
        if (body.enabled != null) {
            sets += "enabled = ${body.enabled}"
        }
        if (sets.isEmpty()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "no updatable fields provided")
        }

        jdbcClient.sql(
            "UPDATE crawl_schedule SET ${sets.joinToString(
                ", ",
            )}, updated_at = now() WHERE crawler = :crawler AND job_id = :jobId",
        )
            .param("crawler", crawler)
            .param("jobId", jobId)
            .update()

        // 런타임 반영 — 미등록 잡이면 500 (legacy 동일)
        try {
            crawlScheduler.apply(crawler, jobId)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message)
        }

        val row = scheduleRepository.findByCrawlerAndJobId(crawler, jobId)!!
        log.info { "Updated schedule (crawler=$crawler, job_id=$jobId, enabled=${row.enabled})" }
        return ApiResponse.ok(attachRuntime(ScheduleItem.from(row)), ResponseMeta(clock.instant()))
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

    private fun buildArraySql(column: String, values: List<String>): String =
        "$column = ARRAY[${values.joinToString(",") { "'${it.replace("'", "''")}'" }}]::text[]"
}
