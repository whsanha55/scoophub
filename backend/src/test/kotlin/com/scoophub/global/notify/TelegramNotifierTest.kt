package com.scoophub.global.notify

import com.scoophub.global.config.ScoophubProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

/** legacy tests/test_telegram.py 포팅 */
@RestClientTest(TelegramNotifier::class)
@EnableConfigurationProperties(ScoophubProperties::class)
class TelegramNotifierTest @Autowired constructor(
    private val notifier: TelegramNotifier,
    private val server: MockRestServiceServer,
) {
    @Test
    fun `send 는 sendMessage 를 HTML 파싱으로 호출`() {
        // given
        server.expect(requestTo("https://api.telegram.org/bot/sendMessage"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.chat_id").value("chat1"))
            .andExpect(jsonPath("$.text").value("hello"))
            .andExpect(jsonPath("$.parse_mode").value("HTML"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        // when
        notifier.send("chat1", null, NotifyMessage("hello"))

        // then
        server.verify()
    }

    @Test
    fun `topic_id 가 있으면 message_thread_id 포함`() {
        // given
        server.expect(requestTo("https://api.telegram.org/bot/sendMessage"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"message_thread_id\":7")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        // when
        notifier.send("chat1", 7, NotifyMessage("hi"))

        // then
        server.verify()
    }

    @Test
    fun `429 응답은 예외로 전파`() {
        // given
        server.expect(requestTo("https://api.telegram.org/bot/sendMessage"))
            .andRespond(withServerError())

        // when & then
        assertThatThrownBy { notifier.send("chat1", null, NotifyMessage("x")) }
        server.verify()
    }

    @Test
    fun `createTopic 는 message_thread_id 를 반환`() {
        // given
        server.expect(requestTo("https://api.telegram.org/bot/createForumTopic"))
            .andExpect(jsonPath("$.name").value("topic"))
            .andRespond(withSuccess("""{"result":{"message_thread_id":42}}""", MediaType.APPLICATION_JSON))

        // when
        val topicId = notifier.createTopic("chat1", "topic")

        // then
        assertThat(topicId).isEqualTo(42)
        server.verify()
    }

    @Test
    fun `4096 초과 텍스트는 분할 발신`() {
        // given
        val longText = "a".repeat(4096) + "b".repeat(10)
        server.expect(
            requestTo("https://api.telegram.org/bot/sendMessage"),
        ).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        server.expect(
            requestTo("https://api.telegram.org/bot/sendMessage"),
        ).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        // when
        notifier.send("chat1", null, NotifyMessage(longText))

        // then
        server.verify()
    }

    @Test
    fun `split 헬퍼`() {
        assertThat(TelegramNotifier.split("short", 4096)).containsExactly("short")
        val parts = TelegramNotifier.split("a".repeat(8200), 4096)
        assertThat(parts).hasSize(3)
        assertThat(parts[0]).hasSize(4096)
        assertThat(parts.sumOf { it.length }).isEqualTo(8200)
    }
}
