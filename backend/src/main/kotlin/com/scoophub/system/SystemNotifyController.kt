package com.scoophub.system

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.notify.vo.NotifyRouteUpdate
import com.scoophub.system.dto.NotifyLogItem
import com.scoophub.system.dto.NotifyRouteItem
import com.scoophub.system.service.SystemNotifyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

/** legacy `system/notify_router.py` — notify_routes CRUD + 발신 테스트 + 발신 이력 */
@RestController
class SystemNotifyController(private val notifyService: SystemNotifyService, private val clock: Clock) {
    data class RouteCreateRequest(
        val category: String = "",
        val purpose: String = "",
        val channel: String = "telegram",
        val chatId: String,
        val topicId: Long? = null,
        val topicName: String = "",
        val enabled: Boolean = true,
    )

    data class RouteUpdateRequest(
        val category: String? = null,
        val purpose: String? = null,
        val channel: String? = null,
        val chatId: String? = null,
        val topicId: Long? = null,
        val topicName: String? = null,
        val enabled: Boolean? = null,
    ) {
        fun toUpdate() = NotifyRouteUpdate(
            category = category,
            purpose = purpose,
            channel = channel,
            chatId = chatId,
            topicId = topicId,
            topicName = topicName,
            enabled = enabled,
        )
    }

    @Tag(name = "Notify")
    @Operation(summary = "전체 notify 라우트 조회")
    @GetMapping("/api/notify/routes")
    fun listRoutes(): ApiResponse<List<NotifyRouteItem>> {
        val routes = notifyService.findAllRoutes().map { NotifyRouteItem.from(it) }
        return ApiResponse.ok(routes, ResponseMeta(clock.instant(), total = routes.size))
    }

    @Tag(name = "Notify")
    @Operation(summary = "notify 라우트 생성")
    @SuperOnly
    @PostMapping("/api/notify/routes")
    fun createRoute(@RequestBody body: RouteCreateRequest): ApiResponse<NotifyRouteItem> {
        val route = notifyService.createRoute(
            category = body.category,
            purpose = body.purpose,
            channel = body.channel,
            chatId = body.chatId,
            topicId = body.topicId,
            topicName = body.topicName,
            enabled = body.enabled,
        )
        return ApiResponse.ok(NotifyRouteItem.from(route), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Notify")
    @Operation(summary = "notify 라우트 수정")
    @SuperOnly
    @PatchMapping("/api/notify/routes/{route_id}")
    fun updateRoute(
        @PathVariable("route_id") routeId: Long,
        @RequestBody body: RouteUpdateRequest,
    ): ApiResponse<NotifyRouteItem> {
        val route = notifyService.updateRoute(routeId, body.toUpdate())
        return ApiResponse.ok(NotifyRouteItem.from(route), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Notify")
    @Operation(summary = "notify 라우트 삭제")
    @SuperOnly
    @DeleteMapping("/api/notify/routes/{route_id}")
    fun deleteRoute(@PathVariable("route_id") routeId: Long): ApiResponse<Map<String, Long>> {
        notifyService.deleteRoute(routeId)
        return ApiResponse.ok(mapOf("deleted" to routeId), ResponseMeta(clock.instant()))
    }

    data class RouteTestData(val routeId: Long, val status: String, val error: String?)

    @Tag(name = "Notify")
    @Operation(summary = "발신 테스트 (해당 라우트로 강제 1회 발신)")
    @SuperOnly
    @PostMapping("/api/notify/routes/{route_id}/test")
    fun testRoute(@PathVariable("route_id") routeId: Long): ApiResponse<RouteTestData> {
        val status = notifyService.testRoute(routeId)
        return ApiResponse.ok(
            RouteTestData(routeId, status?.first ?: "unknown", status?.second),
            ResponseMeta(clock.instant()),
        )
    }

    @Tag(name = "Notify")
    @Operation(summary = "notify 발신 이력 조회")
    @SuperOnly
    @GetMapping("/api/notify/log")
    fun listLogs(
        @RequestParam(name = "route_id") routeId: Long? = null,
        status: String? = null,
        @RequestParam(defaultValue = "100") limit: Int = 100,
    ): ApiResponse<List<NotifyLogItem>> {
        if (status != null && status !in listOf("success", "error")) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "status must be one of [success, error] or omitted",
            )
        }
        val logs = notifyService.findLogs(routeId, status, limit.coerceIn(1, 500)).map { NotifyLogItem.from(it) }
        return ApiResponse.ok(logs, ResponseMeta(clock.instant(), total = logs.size))
    }
}
