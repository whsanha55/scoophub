package com.scoophub.global.notify

import com.scoophub.external.llm.LlmClient
import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.notify.repository.NotifyRouteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** legacy tests/test_notify.py 의 provisioner 파트 포팅 */
class AutoTopicProvisionerTest {
    private val routeRepository = mockk<NotifyRouteRepository>(relaxed = true)
    private val llm = mockk<LlmClient>()
    private val telegram = mockk<TelegramNotifier>()
    private val jsonMapper = JsonMapper.builder().build()

    private fun provisioner(chatId: String = "chat1"): AutoTopicProvisioner {
        val props = ScoophubProperties(
            enableScheduler = false,
            corsOrigins = emptyList(),
            llm = ScoophubProperties.Llm(apiUrl = "", apiKey = "", model = ""),
            auth = ScoophubProperties.Auth(
                allowedEmails = emptySet(),
                superEmails = emptySet(),
                googleClientId = "",
                googleClientSecret = "",
                jwtSecret = "",
                jwtExpireHours = 24,
                bypass = false,
                redirectUrl = "",
                oauthRedirectUri = "",
            ),
            telegram = ScoophubProperties.Telegram(botToken = "tok", defaultChatId = chatId),
            producthuntToken = "",
            youtubeApiKey = "",
        )
        return AutoTopicProvisioner(props, routeRepository, llm, telegram, jsonMapper)
    }

    @Test
    fun `기본 chat_id 미설정이면 자동생성 없음`() {
        // when
        provisioner(chatId = "").ensureRoute("newcat")

        // then
        verify(exactly = 0) { telegram.createTopic(any(), any()) }
    }

    @Test
    fun `매칭 라우트가 있으면 스킵`() {
        // given
        every { routeRepository.existsRoute("hacker_news", "") } returns true

        // when
        provisioner().ensureRoute("hacker_news")

        // then
        verify(exactly = 0) { telegram.createTopic(any(), any()) }
    }

    @Test
    fun `신규 카테고리는 LLM 이름으로 토픽 생성 후 라우트 등록`() {
        // given
        every { routeRepository.existsRoute("newcat", "") } returns false
        every { llm.chat(any(), "newcat") } returns """{"name": "새 카테고리", "emoji": "🆕"}"""
        every { telegram.createTopic("chat1", "🆕 새 카테고리") } returns 77L

        // when
        provisioner().ensureRoute("newcat")

        // then
        verify { routeRepository.insertWildcardRoute("newcat", "chat1", 77L, "새 카테고리") }
    }

    @Test
    fun `LLM 실패 시 raw 폴백으로 발신 유지`() {
        // given
        every { routeRepository.existsRoute("newcat", "") } returns false
        every { llm.chat(any(), any()) } throws RuntimeException("llm down")
        every { telegram.createTopic("chat1", "📢 newcat") } returns 1L

        // when
        provisioner().ensureRoute("newcat")

        // then
        verify { routeRepository.insertWildcardRoute("newcat", "chat1", 1L, "newcat") }
    }

    @Test
    fun `LLM 응답 파싱 실패 시에도 raw 폴백`() {
        // given
        every { routeRepository.existsRoute("newcat", "") } returns false
        every { llm.chat(any(), any()) } returns "not json"
        every { telegram.createTopic(any(), any()) } returns 1L

        // when
        provisioner().ensureRoute("newcat")

        // then
        verify { routeRepository.insertWildcardRoute("newcat", "chat1", 1L, "newcat") }
    }

    @Test
    fun `createTopic 실패는 전파 — 호출부가 크롤 보호`() {
        // given
        every { routeRepository.existsRoute("newcat", "") } returns false
        every { llm.chat(any(), any()) } throws RuntimeException("llm down")
        every { telegram.createTopic(any(), any()) } throws RuntimeException("telegram down")

        // when & then
        assertThatThrownBy { provisioner().ensureRoute("newcat") }
            .isInstanceOf(RuntimeException::class.java)
        verify(exactly = 0) { routeRepository.insertWildcardRoute(any(), any(), any(), any()) }
    }

    @Test
    fun `빈 이름 응답도 raw 폴백`() {
        // given
        every { routeRepository.existsRoute("newcat", "") } returns false
        every { llm.chat(any(), any()) } returns """{"name": "  ", "emoji": ""}"""
        every { telegram.createTopic(any(), any()) } returns 3L

        // when
        provisioner().ensureRoute("newcat")

        // then — emoji 기본값 📢, 이름은 raw
        verify { telegram.createTopic("chat1", "📢 newcat") }
        assertThat(true).isTrue()
    }
}
