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
    fun dispatch(category: String, purpose: String, payloadKey: String, message: NotifyMessage) {
        for (route in routeRepository.lookup(category, purpose)) {
            sendOne(route, payloadKey, message)
        }
    }

    private fun sendOne(route: NotifyRouteEntity, payloadKey: String, message: NotifyMessage) {
        // 중복: 직전 success 있으면 스킵 (error 는 재시도 허용)
        if (payloadKey.isNotEmpty() && logRepository.existsSuccess(route.id, payloadKey)) {
            return
        }

        val notifier = notifierFor(route.channel)

        var topicId = route.topicId
        if (topicId == null && route.topicName.isNotEmpty()) {
            topicId = try {
                val created = notifier.createTopic(route.chatId, route.topicName)
                routeRepository.updateTopicId(route.id, created)
                created
            } catch (e: Exception) {
                log.warn { "create_topic failed (route ${route.id}): ${e.message}" }
                writeLog(route.id, payloadKey, "error", "create_topic: ${e.message}")
                return
            }
        }

        try {
            notifier.send(route.chatId, topicId, message)
            writeLog(route.id, payloadKey, "success", null)
        } catch (e: Exception) {
            log.warn { "notify send failed (route ${route.id}): ${e.message}" }
            writeLog(route.id, payloadKey, "error", e.message)
        }
    }

    /** 발신 시점에 채널 해석 — 빈 생성 시점 프로퍼티 읽기(@MockkBean 호환) 회피 */
    private fun notifierFor(channel: String): Notifier = notifiers.firstOrNull { it.channel == channel }
        ?: throw IllegalArgumentException("unsupported notify channel: '$channel'")

    private fun writeLog(routeId: Long, payloadKey: String, status: String, error: String?) {
        try {
            logRepository.upsertLog(routeId, payloadKey, status, error)
        } catch (e: Exception) {
            log.error { "notify_log write failed: ${e.message}" }
        }
    }
}
