package com.scoophub.external.llm

import com.scoophub.global.config.ScoophubProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.time.Duration

private val log = KotlinLogging.logger {}

/** legacy `core/llm/client.py` — OpenAI Chat Completions 호환(OpenRouter) 호출 클라이언트 */
@Component
class LlmClient(private val props: ScoophubProperties) {

    // LLM 은 수 분까지 걸릴 수 있어 공통 timeout(10s) 대신 전용 factory
    private val restClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
                .apply { setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)) },
        )
        .build()

    private val newsRestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
                .apply { setReadTimeout(Duration.ofSeconds(20)) },
        ).build()

    fun chatNews(systemPrompt: String, userPrompt: String): String =
        chatWith(newsRestClient, systemPrompt, userPrompt, true)

    fun chat(systemPrompt: String, userPrompt: String): String = chatWith(restClient, systemPrompt, userPrompt, false)

    private fun chatWith(
        client: RestClient,
        systemPrompt: String,
        userPrompt: String,
        disableThinking: Boolean,
    ): String {
        log.info { "LlmClient.chat 시작 - model=${props.llm.model}" }
        val response = client.post()
            .uri(props.llm.apiUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers ->
                if (props.llm.apiKey.isNotEmpty()) {
                    headers.setBearerAuth(props.llm.apiKey)
                }
            }
            .body(
                buildMap<String, Any> {
                    putAll(
                        mapOf(
                            "model" to props.llm.model,
                            "messages" to listOf(
                                mapOf("role" to "system", "content" to systemPrompt),
                                mapOf("role" to "user", "content" to userPrompt),
                            ),
                            "temperature" to 0.3,
                        ),
                    )
                    if (disableThinking) put("thinking", mapOf("type" to "disabled"))
                },
            )
            .retrieve()
            .body(JsonNode::class.java)
            ?: error("empty LLM response")

        return try {
            val content = response["choices"][0]["message"]["content"].asString()
            log.info { "LlmClient.chat 완료 - 응답 길이=${content.length}" }
            content
        } catch (e: Exception) {
            throw IllegalStateException("Invalid LLM response structure: ${e.message}")
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 600L
    }
}
