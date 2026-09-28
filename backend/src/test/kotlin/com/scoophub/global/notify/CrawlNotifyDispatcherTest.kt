package com.scoophub.global.notify

import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.repository.CrawlDataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** legacy tests/test_notify.py 의 dispatch 게이트 파트 포팅 */
class CrawlNotifyDispatcherTest {
    private val card = mockk<NotifyCard>()
    private val router = mockk<NotifyRouter>(relaxed = true)
    private val provisioner = mockk<AutoTopicProvisioner>(relaxed = true)
    private val crawlDataRepository = mockk<CrawlDataRepository>()
    private val crawlDataStore = mockk<CrawlDataStore>(relaxed = true)

    private fun dispatcher(
        token: String = "tok",
        now: Instant = Instant.parse("2027-01-01T00:00:00Z"), // KST 09:00
    ): CrawlNotifyDispatcher {
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
            telegram = ScoophubProperties.Telegram(botToken = token, defaultChatId = ""),
            producthuntToken = "",
            youtubeApiKey = "",
        )
        return CrawlNotifyDispatcher(
            props,
            card,
            router,
            provisioner,
            crawlDataRepository,
            crawlDataStore,
            Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    private val result = CrawlResult(itemsFetched = 10, itemsNew = 3, newArticleIds = listOf(7L, 9L, 42L))

    private fun stubCard() {
        every { card.enrich(any(), any(), any(), any()) } returns "enriched"
    }

    @Test
    fun `토큰 미설정이면 스킵`() {
        // when
        dispatcher(token = "").dispatch("hacker_news", "", result)

        // then
        verify(exactly = 0) { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `stock 은 스킵 (별도 티켓)`() {
        // when
        dispatcher().dispatch("stock", "", result)

        // then
        verify(exactly = 0) { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `신규 0건은 스킵`() {
        // when
        dispatcher().dispatch("hacker_news", "", CrawlResult(itemsFetched = 5, itemsNew = 0))

        // then
        verify(exactly = 0) { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `result null 은 스킵`() {
        // when
        dispatcher().dispatch("hacker_news", "", null)

        // then
        verify(exactly = 0) { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `enrich null 이면 발신 스킵`() {
        // given
        every { card.enrich(any(), any(), any(), any()) } returns null

        // when
        dispatcher().dispatch("hacker_news", "", result)

        // then
        verify(exactly = 0) { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `payloadKey 미지정시 newIds 최댓값으로 dedup 키`() {
        // given
        stubCard()

        // when
        dispatcher().dispatch("hacker_news", "detail", result)

        // then
        verify {
            router.dispatch("hacker_news", "detail", "hacker_news:detail:42", NotifyMessage("enriched"))
        }
    }

    @Test
    fun `newIds 없으면 빈 키로 매 run 발신`() {
        // given
        stubCard()
        every { crawlDataRepository.findByCategoryAndPurposeAndKey("weather", "notify_sent", any()) } returns null
        val snapshot = CrawlResult(itemsFetched = 1, itemsNew = 1)

        // when
        dispatcher().dispatch("weather", "", snapshot)

        // when & then — weather 게이트(7시+) 통과 가정하에 빈 키
        verify { router.dispatch("weather", "", "", any()) }
    }

    @Test
    fun `weather 는 KST 7시 이전 스킵`() {
        // given — KST 06:59
        val before7 = ZonedDateTime.of(2027, 1, 1, 6, 59, 0, 0, ZoneOffset.ofHours(9)).toInstant()

        // when
        dispatcher(now = before7).dispatch("weather", "", result)

        // then
        verify(exactly = 0) { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `weather 는 오늘 이미 발신했으면 스킵`() {
        // given — KST 2027-01-01 09:00
        every {
            crawlDataRepository.findByCategoryAndPurposeAndKey("weather", "notify_sent", "2027-01-01")
        } returns mockk()

        // when
        dispatcher().dispatch("weather", "", result)

        // then
        verify(exactly = 0) { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `weather 발신 성공 후 notify_sent state upsert`() {
        // given
        stubCard()
        every { crawlDataRepository.findByCategoryAndPurposeAndKey("weather", "notify_sent", any()) } returns null

        // when
        dispatcher().dispatch("weather", "", result)

        // then
        verify { crawlDataStore.upsert("weather", "notify_sent", "2027-01-01", any()) }
    }

    @Test
    fun `provisioner 실패해도 발신은 계속`() {
        // given
        stubCard()
        every { provisioner.ensureRoute(any(), any()) } throws IllegalStateException("boom")

        // when
        dispatcher().dispatch("hacker_news", "", result)

        // then
        verify { router.dispatch(any(), any(), any(), any()) }
    }

    @Test
    fun `발신 중 예외는 삼킨다`() {
        // given
        stubCard()
        every { router.dispatch(any(), any(), any(), any()) } throws RuntimeException("send fail")

        // when & then — 예외 전파 없음
        val dispatched = runCatching { dispatcher().dispatch("hacker_news", "", result) }
        assertThat(dispatched.isSuccess).isTrue()
    }
}
