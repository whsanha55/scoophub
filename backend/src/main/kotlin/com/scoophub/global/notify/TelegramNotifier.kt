package com.scoophub.global.notify

import com.scoophub.global.config.ScoophubProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/** legacy `core/notify/telegram.py` — Telegram Bot API 발신 (sendMessage + createForumTopic) */
@Component
class TelegramNotifier(props: ScoophubProperties, restClientBuilder: RestClient.Builder) : Notifier {
    private val restClient = restClientBuilder
        .baseUrl("https://api.telegram.org/bot${props.telegram.botToken}")
        .build()

    override val channel = "telegram"

    override fun send(chatId: String, topicId: Long?, message: NotifyMessage) {
        for (chunk in split(message.text, MAX_TEXT)) {
            val payload = buildMap {
                put("chat_id", chatId)
                put("text", chunk)
                put("parse_mode", "HTML")
                topicId?.let { put("message_thread_id", it) }
            }
            post("sendMessage", payload)
        }
    }

    override fun createTopic(chatId: String, name: String): Long {
        val data = post("createForumTopic", mapOf("chat_id" to chatId, "name" to name))
        return data["result"]["message_thread_id"].asLong()
    }

    private fun post(method: String, payload: Map<String, Any>): JsonNode = restClient.post()
        .uri("/$method")
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload)
        .retrieve()
        .body(JsonNode::class.java)
        ?: error("empty telegram response: $method")

    companion object {
        /** Telegram sendMessage 한도. 초과 시 분할 발신 */
        private const val MAX_TEXT = 4096

        fun split(text: String, maxLen: Int): List<String> = if (text.length <= maxLen) {
            listOf(text)
        } else {
            text.chunked(maxLen)
        }
    }
}
