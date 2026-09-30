package com.scoophub.global.notify

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** legacy tests/test_notify.py 의 router 파트 포팅 — DB 시드 필요 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class NotifyRouterTest @Autowired constructor(private val router: NotifyRouter, private val jdbcClient: JdbcClient) {
    @MockkBean
    private lateinit var telegram: TelegramNotifier

    @BeforeEach
    fun clean() {
        jdbcClient.sql("DELETE FROM notify_log").update()
        jdbcClient.sql("DELETE FROM notify_routes").update()
        every { telegram.send(any(), any(), any()) } returns Unit
        every { telegram.channel } returns "telegram"
    }

    private fun insertRoute(
        category: String,
        purpose: String,
        enabled: Boolean = true,
        topicId: Long? = null,
        topicName: String = "",
        chatId: String = "chat1",
    ): Long = jdbcClient.sql(
        """
            INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled)
            VALUES (:category, :purpose, 'telegram', :chatId, :topicId, :topicName, :enabled) RETURNING id
            """,
    )
        .param("category", category)
        .param("purpose", purpose)
        .param("chatId", chatId)
        .param("topicId", topicId)
        .param("topicName", topicName)
        .param("enabled", enabled)
        .query { rs, _ -> rs.getLong(1) }
        .single()

    private fun lastLogStatus(routeId: Long): String? =
        jdbcClient.sql("SELECT status FROM notify_log WHERE route_id = :routeId")
            .param("routeId", routeId)
            .query { rs, _ -> rs.getString("status") }
            .optional()
            .orElse(null)

    @Test
    fun `exact 매칭과 wildcard 폴백이 모두 발신`() {
        // given
        insertRoute("hacker_news", "")
        insertRoute("", "")

        // when
        router.dispatch("hacker_news", "detail", "pk", NotifyMessage("hi"))

        // then
        verify(exactly = 2) { telegram.send(any(), any(), any()) }
    }

    @Test
    fun `매칭 없으면 발신 없음`() {
        insertRoute("weather", "snapshot")

        router.dispatch("hacker_news", "detail", "pk", NotifyMessage("hi"))

        verify(exactly = 0) { telegram.send(any(), any(), any()) }
    }

    @Test
    fun `비활성 라우트는 스킵`() {
        insertRoute("hacker_news", "", enabled = false)

        router.dispatch("hacker_news", "", "pk", NotifyMessage("hi"))

        verify(exactly = 0) { telegram.send(any(), any(), any()) }
    }

    @Test
    fun `동일 payloadKey 성공 발신은 1회만`() {
        val routeId = insertRoute("hacker_news", "")

        router.dispatch("hacker_news", "", "pk", NotifyMessage("a"))
        router.dispatch("hacker_news", "", "pk", NotifyMessage("b"))

        verify(exactly = 1) { telegram.send(any(), any(), any()) }
        org.assertj.core.api.Assertions.assertThat(lastLogStatus(routeId)).isEqualTo("success")
    }

    @Test
    fun `빈 payloadKey 는 dedup 미적용 — 매번 발신`() {
        insertRoute("hacker_news", "")

        router.dispatch("hacker_news", "", "", NotifyMessage("a"))
        router.dispatch("hacker_news", "", "", NotifyMessage("b"))

        verify(exactly = 2) { telegram.send(any(), any(), any()) }
    }

    @Test
    fun `발신 실패는 error 로깅 후 재시도 허용`() {
        // given
        val routeId = insertRoute("hacker_news", "")
        var call = 0
        every { telegram.send(any(), any(), any()) } answers {
            call++
            if (call == 1) throw RuntimeException("fail")
        }

        // when
        router.dispatch("hacker_news", "", "pk", NotifyMessage("a"))
        router.dispatch("hacker_news", "", "pk", NotifyMessage("b"))

        // then — error 기록 후 재시도에서 성공
        verify(exactly = 2) { telegram.send(any(), any(), any()) }
        org.assertj.core.api.Assertions.assertThat(lastLogStatus(routeId)).isEqualTo("success")
    }

    @Test
    fun `topic_id null + topic_name 있으면 자동 생성 후 반영`() {
        // given
        val routeId = insertRoute("hacker_news", "", topicId = null, topicName = "HN 토픽")
        every { telegram.createTopic("chat1", "HN 토픽") } returns 42L

        // when
        router.dispatch("hacker_news", "", "pk", NotifyMessage("hi"))

        // then
        verify { telegram.send("chat1", 42L, any()) }
        val topicId = jdbcClient.sql("SELECT topic_id FROM notify_routes WHERE id = :id")
            .param("id", routeId)
            .query { rs, _ -> rs.getLong("topic_id") }
            .single()
        org.assertj.core.api.Assertions.assertThat(topicId).isEqualTo(42L)
    }

    @Test
    fun `토픽 생성 실패는 error 로그 후 발신 중단`() {
        // given
        val routeId = insertRoute("hacker_news", "", topicId = null, topicName = "X")
        every { telegram.createTopic(any(), any()) } throws RuntimeException("boom")

        // when
        router.dispatch("hacker_news", "", "pk", NotifyMessage("hi"))

        // then
        verify(exactly = 0) { telegram.send(any(), any(), any()) }
        org.assertj.core.api.Assertions.assertThat(lastLogStatus(routeId)).isEqualTo("error")
    }

    @Test
    fun `묶음 발송은 한 메시지를 보내고 기사별 성공 키로 중복을 막는다`() {
        // given
        val routeId = insertRoute("news", "alpaca")
        val cards = listOf("news:alpaca:1" to NotifyMessage("기사 1"), "news:alpaca:2" to NotifyMessage("기사 2"))
        // when
        val first = router.dispatchBatch("news", "alpaca", cards)
        val second = router.dispatchBatch("news", "alpaca", cards)
        // then
        org.assertj.core.api.Assertions.assertThat(first).isTrue()
        org.assertj.core.api.Assertions.assertThat(second).isTrue()
        verify(exactly = 1) { telegram.send(any(), any(), NotifyMessage("기사 1\n\n기사 2")) }
        val count = jdbcClient.sql("SELECT COUNT(*) FROM notify_log WHERE route_id = :route AND status = 'success'")
            .param("route", routeId).query(Int::class.java).single()
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(2)
    }
}
