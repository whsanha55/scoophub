package com.scoophub.system

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.notify.NotifyCard
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.system.dto.NotifyLogItem
import com.scoophub.system.dto.NotifyRouteItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
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
import java.util.UUID

private val log = KotlinLogging.logger {}

/** legacy `system/notify_router.py` — notify_routes CRUD + 발신 테스트 + 발신 이력 */
@RestController
class SystemNotifyController(
    private val notifyRouter: NotifyRouter,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val channels = listOf("telegram", "discord", "email")

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
    )

    @Tag(name = "Notify")
    @Operation(summary = "전체 notify 라우트 조회")
    @GetMapping("/api/notify/routes")
    fun listRoutes(): ApiResponse<List<NotifyRouteItem>> {
        val routes = jdbcClient.sql(
            "SELECT id, category, purpose, channel, chat_id, topic_id, topic_name, enabled, created_at, updated_at " +
                "FROM notify_routes ORDER BY id",
        ).query { rs, _ -> NotifyRouteItem.of(rs) }.list()
        return ApiResponse.ok(routes, ResponseMeta(clock.instant(), total = routes.size))
    }

    @Tag(name = "Notify")
    @Operation(summary = "notify 라우트 생성")
    @SuperOnly
    @PostMapping("/api/notify/routes")
    fun createRoute(@RequestBody body: RouteCreateRequest): ApiResponse<NotifyRouteItem> {
        if (body.channel !in channels) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "channel must be one of $channels")
        }
        val id = jdbcClient.sql(
            """
            INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled)
            VALUES (:category, :purpose, :channel, :chatId, :topicId, :topicName, :enabled)
            RETURNING id
            """,
        )
            .param("category", body.category)
            .param("purpose", body.purpose)
            .param("channel", body.channel)
            .param("chatId", body.chatId)
            .param("topicId", body.topicId)
            .param("topicName", body.topicName)
            .param("enabled", body.enabled)
            .query { rs, _ -> rs.getLong(1) }
            .single()
        log.info { "Created notify route (id=$id category=${body.category})" }
        return ApiResponse.ok(findRoute(id)!!, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Notify")
    @Operation(summary = "notify 라우트 수정")
    @SuperOnly
    @PatchMapping("/api/notify/routes/{route_id}")
    fun updateRoute(
        @PathVariable("route_id") routeId: Long,
        @RequestBody body: RouteUpdateRequest,
    ): ApiResponse<NotifyRouteItem> {
        if (findRoute(routeId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found")
        }
        if (body.channel != null && body.channel !in channels) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "channel must be one of $channels")
        }

        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        listOf(
            "category" to body.category,
            "purpose" to body.purpose,
            "channel" to body.channel,
            "chat_id" to body.chatId,
            "topic_id" to body.topicId,
            "topic_name" to body.topicName,
            "enabled" to body.enabled,
        ).forEach { (column, value) ->
            if (value != null) {
                val name = "p${params.size}"
                sets += "$column = :$name"
                params[name] = value
            }
        }
        if (sets.isEmpty()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "no updatable fields provided")
        }
        jdbcClient.sql(
            "UPDATE notify_routes SET ${sets.joinToString(", ")}, updated_at = now() WHERE id = :id",
        )
            .param("id", routeId)
            .params(params)
            .update()
        log.info { "Updated notify route (id=$routeId)" }
        return ApiResponse.ok(findRoute(routeId)!!, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Notify")
    @Operation(summary = "notify 라우트 삭제")
    @SuperOnly
    @DeleteMapping("/api/notify/routes/{route_id}")
    fun deleteRoute(@PathVariable("route_id") routeId: Long): ApiResponse<Map<String, Long>> {
        val deleted = jdbcClient.sql("DELETE FROM notify_routes WHERE id = :id")
            .param("id", routeId)
            .update()
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found")
        }
        log.info { "Deleted notify route (id=$routeId)" }
        return ApiResponse.ok(mapOf("deleted" to routeId), ResponseMeta(clock.instant()))
    }

    data class RouteTestData(val routeId: Long, val status: String, val error: String?)

    @Tag(name = "Notify")
    @Operation(summary = "발신 테스트 (해당 라우트로 강제 1회 발신)")
    @SuperOnly
    @PostMapping("/api/notify/routes/{route_id}/test")
    fun testRoute(@PathVariable("route_id") routeId: Long): ApiResponse<RouteTestData> {
        val route = findRoute(routeId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found")

        val payloadKey = "test:${UUID.randomUUID().toString().replace("-", "")}" // dedup 우회용 고유키
        // category 는 DB 외부값 → parse_mode=HTML 발신이므로 escape 필수
        val categoryLabel = NotifyCard.escapeHtml(route.category.ifEmpty { "(default)" })
        notifyRouter.dispatch(
            route.category,
            route.purpose,
            payloadKey,
            NotifyMessage(text = "[test] $categoryLabel 발신 테스트"),
        )
        val status = jdbcClient.sql(
            "SELECT status, error FROM notify_log WHERE route_id = :id AND payload_key = :key",
        )
            .param("id", routeId)
            .param("key", payloadKey)
            .query { rs, _ -> rs.getString("status") to rs.getString("error") }
            .optional()
            .orElse(null)
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
        val clamped = limit.coerceIn(1, 500)

        var sql = """
            SELECT l.id, l.route_id, l.payload_key, l.status, l.error, l.sent_at, r.category, r.purpose
            FROM notify_log l LEFT JOIN notify_routes r ON r.id = l.route_id
        """
        val conditions = mutableListOf<String>()
        routeId?.let { conditions += "l.route_id = :routeId" }
        status?.let { conditions += "l.status = :status" }
        if (conditions.isNotEmpty()) {
            sql += " WHERE ${conditions.joinToString(" AND ")}"
        }
        sql += " ORDER BY l.sent_at DESC LIMIT :limit"

        var spec = jdbcClient.sql(sql).param("limit", clamped)
        routeId?.let { spec = spec.param("routeId", it) }
        status?.let { spec = spec.param("status", it) }
        val logs = spec.query { rs, _ -> NotifyLogItem.of(rs) }.list()
        return ApiResponse.ok(logs, ResponseMeta(clock.instant(), total = logs.size))
    }

    private fun findRoute(id: Long): NotifyRouteItem? = jdbcClient.sql(
        "SELECT id, category, purpose, channel, chat_id, topic_id, topic_name, enabled, created_at, updated_at " +
            "FROM notify_routes WHERE id = :id",
    )
        .param("id", id)
        .query { rs, _ -> NotifyRouteItem.of(rs) }
        .optional()
        .orElse(null)
}
