package com.scoophub.system

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.system.dto.ScheduleItem
import com.scoophub.system.service.SystemScheduleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/** legacy `system/schedules_router.py` — crawl_schedule 관리 + 런타임 재스케줄 */
@RestController
class SystemSchedulesController(private val scheduleService: SystemScheduleService, private val clock: Clock) {
    data class ScheduleUpdateRequest(
        val schedules: List<String>? = null,
        val scheduleMinutes: Int? = null,
        val enabled: Boolean? = null,
    )

    @Tag(name = "Schedules")
    @Operation(summary = "전체 스케줄 조회")
    @GetMapping("/api/schedules")
    fun listSchedules(): ApiResponse<List<ScheduleItem>> {
        val items = scheduleService.findAll()
        return ApiResponse.ok(items, ResponseMeta(clock.instant(), total = items.size))
    }

    @Tag(name = "Schedules")
    @Operation(summary = "단일 스케줄 조회")
    @GetMapping("/api/schedules/{crawler}/{job_id}")
    fun getSchedule(@PathVariable crawler: String, @PathVariable("job_id") jobId: String): ApiResponse<ScheduleItem> =
        ApiResponse.ok(scheduleService.find(crawler, jobId), ResponseMeta(clock.instant()))

    @Tag(name = "Schedules")
    @Operation(summary = "스케줄 수정")
    @SuperOnly
    @PatchMapping("/api/schedules/{crawler}/{job_id}")
    fun updateSchedule(
        @PathVariable crawler: String,
        @PathVariable("job_id") jobId: String,
        @RequestBody body: ScheduleUpdateRequest,
    ): ApiResponse<ScheduleItem> {
        val item = scheduleService.update(crawler, jobId, body.schedules, body.scheduleMinutes, body.enabled)
        return ApiResponse.ok(item, ResponseMeta(clock.instant()))
    }
}
