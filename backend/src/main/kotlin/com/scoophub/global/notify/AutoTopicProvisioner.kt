package com.scoophub.global.notify

import com.scoophub.external.llm.LlmClient
import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.notify.repository.NotifyRouteRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val log = KotlinLogging.logger {}

/**
 * legacy `core/notify/provisioner.py` — 라우트 부재 시 자동 토픽 생성 (lazy).
 * LLM 실패/미설정 → raw 폴백(category 그대로, 📢) 로 생성해 발신 유지(ON 정책).
 */
@Component
class AutoTopicProvisioner(
    private val props: ScoophubProperties,
    private val routeRepository: NotifyRouteRepository,
    private val llm: LlmClient,
    private val telegram: TelegramNotifier,
    private val jsonMapper: JsonMapper,
) {
    fun ensureRoute(category: String, detail: String = "") {
        // 폭증 가드: 기본 chat_id 미설정 → 자동생성 없음
        val chatId = props.telegram.defaultChatId
        if (chatId.isEmpty()) {
            return
        }

        // 매칭 라우트 있으면 반환 (router.lookup 과 동일 조건)
        if (routeRepository.existsRoute(category, detail)) {
            return
        }

        val (name, emoji) = topicName(category)
        // create_topic 실패는 그대로 전파 → 호출부가 크롤 보호
        val topicId = telegram.createTopic(chatId, "$emoji $name")
        routeRepository.insertWildcardRoute(category, chatId, topicId, name)
    }

    /** LLM 한국어 (name, emoji). 실패 → raw 폴백(category, 📢) */
    private fun topicName(category: String): Pair<String, String> = try {
        val raw = llm.chat(SYSTEM_PROMPT, category)
        val data: JsonNode = jsonMapper.readTree(raw)
        val name = data["name"].asString().trim()
        val emoji = data["emoji"]?.takeIf { !it.isNull }?.asString()?.trim()?.ifEmpty { null } ?: RAW_EMOJI
        if (name.isEmpty()) {
            throw IllegalStateException("empty name")
        }
        name to emoji
    } catch (e: Exception) {
        log.info { "auto provision LLM failed, raw fallback (category=$category): ${e.message}" }
        category to RAW_EMOJI
    }

    companion object {
        private const val RAW_EMOJI = "📢"
        private val SYSTEM_PROMPT = """
            크롤 카테고리명을 한국어 토픽 이름(명사구, 10자 이내)과 이모지 1개로 변환한다.
            JSON {"name": "...", "emoji": "..."} 형태로만 출력한다.
        """.trimIndent()
    }
}
