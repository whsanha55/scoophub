package com.scoophub.global.notify

import com.scoophub.global.notify.entity.NotifyRouteEntity
import com.scoophub.global.notify.repository.NotifyLogRepository
import com.scoophub.global.notify.repository.NotifyRouteRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * legacy `core/notify/router.py` — (category, purpose) → 라우트 조회 / 중복방지 / topic 자동생성 / 발신.
 * 동일 (route_id, payload_key) 직전 success 발신이 있으면 스킵. error 는 재시도 허용.
 * 쓰기(updateTopicId/upsertLog)는 각자 짧은 트랜잭션으로 실행(발신 HTTP 를 tx 로 감싸지 않는다).
 */
@Component
class NotifyRouter(
    private val routeRepository: NotifyRouteRepository,
    private val logRepository: NotifyLogRepository,
    private val notifiers: List<Notifier>,
) {
    private val newsSendSlots = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun paceNews(chatId: String) {
        val now = System.nanoTime()
        val reserved = newsSendSlots.compute(chatId) { _, previous -> maxOf(now, previous ?: now) + 3_100_000_000L }
        val delay = requireNotNull(reserved) - 3_100_000_000L - now
        if (delay > 0) Thread.sleep(java.time.Duration.ofNanos(delay))
    }

    fun dispatch(category: String, purpose: String, payloadKey: String, message: NotifyMessage) {
        dispatchConfirmed(category, purpose, payloadKey, message)
    }

    fun dispatchConfirmed(category: String, purpose: String, payloadKey: String, message: NotifyMessage): Boolean =
        dispatchBatch(category, purpose, listOf(payloadKey to message))

    /** One message per route, but an independent success key for each article. */
    fun dispatchBatch(category: String, purpose: String, messages: List<Pair<String, NotifyMessage>>): Boolean {
        val routes = routeRepository.lookup(category, purpose)
        if (routes.isEmpty()) return false
        var success = true
        for (route in routes) {
            val pending = messages.filter { (key, _) -> key.isEmpty() || !logRepository.existsSuccess(route.id, key) }
            if (pending.isEmpty()) continue
            if (!sendOne(route, pending)) success = false
        }
        return success
    }

    private fun sendOne(route: NotifyRouteEntity, messages: List<Pair<String, NotifyMessage>>): Boolean {
        val notifier = notifierFor(route.channel)
        var topicId = route.topicId
        if (topicId == null && route.topicName.isNotEmpty()) {
            topicId = try {
                val created = notifier.createTopic(route.chatId, route.topicName)
                routeRepository.updateTopicId(route.id, created)
                created
            } catch (e: Exception) {
                log.warn { "create_topic failed (route ${route.id}): ${e.message}" }
                messages.forEach { (key, _) -> writeLog(route.id, key, "error", "create_topic: ${e.message}") }
                return false
            }
        }
        // Preserve article keys even when the Telegram message contains multiple cards.
        val chunks = mutableListOf<MutableList<Pair<String, NotifyMessage>>>()
        for (message in messages) {
            val current = chunks.lastOrNull()
            if (current == null || current.sumOf { it.second.text.length + 2 } + message.second.text.length > 3500) {
                chunks += mutableListOf(message)
            } else {
                current += message
            }
        }
        var success = true
        for (chunk in chunks) {
            try {
                if (route.channel == "telegram" && chunk.any { it.first.startsWith("news:") }) paceNews(route.chatId)
                notifier.send(route.chatId, topicId, NotifyMessage(chunk.joinToString("\n\n") { it.second.text }))
                chunk.forEach { (key, _) -> if (!writeLog(route.id, key, "success", null)) success = false }
            } catch (e: Exception) {
                log.warn { "notify send failed (route ${route.id}): ${e.message}" }
                chunk.forEach { (key, _) -> writeLog(route.id, key, "error", e.message) }
                success = false
            }
        }
        return success
    }

    /** 발신 시점에 채널 해석 — 빈 생성 시점 프로퍼티 읽기(@MockkBean 호환) 회피 */
    private fun notifierFor(channel: String): Notifier = notifiers.firstOrNull { it.channel == channel }
        ?: throw IllegalArgumentException("unsupported notify channel: '$channel'")

    private fun writeLog(routeId: Long, payloadKey: String, status: String, error: String?): Boolean {
        try {
            logRepository.upsertLog(routeId, payloadKey, status, error)
            return true
        } catch (e: Exception) {
            log.error { "notify_log write failed: ${e.message}" }
            return false
        }
    }
}
