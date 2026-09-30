package com.scoophub.system.service

import com.scoophub.global.notify.NotifyCard
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.global.notify.repository.NotifyQueryRepository
import com.scoophub.global.notify.vo.NotifyLogRow
import com.scoophub.global.notify.vo.NotifyRouteRow
import com.scoophub.global.notify.vo.NotifyRouteUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class SystemNotifyService(private val notifyRouter: NotifyRouter, private val repository: NotifyQueryRepository) {
    fun findAllRoutes(): List<NotifyRouteRow> = repository.findAllRoutes()

    fun createRoute(
        category: String,
        purpose: String,
        channel: String,
        chatId: String,
        topicId: Long?,
        topicName: String,
        enabled: Boolean,
    ): NotifyRouteRow {
        validateChannel(channel)
        val id = repository.insertRoute(category, purpose, channel, chatId, topicId, topicName, enabled)
        log.info { "Created notify route (id=$id category=$category)" }
        return requireNotNull(repository.findRoute(id))
    }

    fun updateRoute(id: Long, update: NotifyRouteUpdate): NotifyRouteRow {
        if (repository.findRoute(id) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found")
        }
        update.channel?.let { validateChannel(it) }
        if (!repository.updateRoute(id, update)) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "no updatable fields provided")
        }
        log.info { "Updated notify route (id=$id)" }
        return requireNotNull(repository.findRoute(id))
    }

    fun deleteRoute(id: Long) {
        if (repository.deleteRoute(id) == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found")
        }
        log.info { "Deleted notify route (id=$id)" }
    }

    /** 해당 라우트로 강제 1회 발신 후 notify_log 결과 (status, error) */
    fun testRoute(id: Long): Pair<String, String?>? {
        val route = repository.findRoute(id)
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
        return repository.findLogStatus(id, payloadKey)
    }

    fun findLogs(routeId: Long?, status: String?, limit: Int): List<NotifyLogRow> =
        repository.findLogs(routeId, status, limit)

    private fun validateChannel(channel: String) {
        if (channel !in CHANNELS) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "channel must be one of $CHANNELS")
        }
    }

    companion object {
        private val CHANNELS = listOf("telegram", "discord", "email")
    }
}
