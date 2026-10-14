package com.scoophub.hotdeal

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.llm.LlmClient
import com.scoophub.global.notify.AutoTopicProvisioner
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.facade.HotdealJudgmentFacade
import com.scoophub.hotdeal.facade.HotdealNotifyFacade
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.service.HotdealFeedbackService
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.service.HotdealScoringService
import io.mockk.every
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

@SpringBootTest(properties = ["scoophub.telegram.bot-token=test-token"])
@Import(TestcontainersConfiguration::class)
class HotdealNotifyTest @Autowired constructor(
    private val notifyFacade: HotdealNotifyFacade,
    private val scoringService: HotdealScoringService,
    private val judgmentFacade: HotdealJudgmentFacade,
    private val feedbackService: HotdealFeedbackService,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
    itemService: HotdealItemService,
    profileRepository: HotdealInterestProfileRepository,
) {
    private val fixtures = HotdealTestFixtures(jdbcClient, itemService, profileRepository)

    @MockkBean
    private lateinit var router: NotifyRouter

    @MockkBean
    private lateinit var provisioner: AutoTopicProvisioner

    // 스텁 없는 호출은 예외 → 판정은 키워드 매칭 대체 판정으로 남는다
    @MockkBean
    private lateinit var llm: LlmClient

    @BeforeEach
    fun setUp() {
        fixtures.clean()
        fixtures.saveEnoughInterests()
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 5.0)
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "모니터", 4.0)
        val now = clock.instant()
        fixtures.saveItem("1", "SSD 2TB 특가", votes = 10, itemAt = now.minusSeconds(600)) // 5.0 + 1.0
        fixtures.saveItem("2", "모니터 27인치", votes = 15, itemAt = now.minusSeconds(900)) // 4.0 + 1.5
        fixtures.saveItem("3", "모니터 오래된 딜", votes = 30, itemAt = now.minusSeconds(8 * 3600)) // 6시간 지남
        scoringService.evaluateRecentItems()
        judgmentFacade.judgePending()
        justRun { provisioner.ensureRoute(any(), any()) }
        every { router.dispatchConfirmed(any(), any(), any(), any()) } returns true
        // 조용한 시간이 테스트 실행 시각에 걸리지 않게 비운다 (00:00~00:00)
        updateSettings(
            """{"enabled": true, "score_threshold": 5.0, "daily_limit": 5, "quiet_start": "00:00", "quiet_end": "00:00"}""",
        )
    }

    @AfterEach
    fun restoreSettings() = updateSettings("""{"enabled": false}""")

    private fun updateSettings(params: String) {
        jdbcClient.sql("UPDATE crawl_config SET params = CAST(:params AS jsonb) WHERE crawler = 'hotdeal_notify'")
            .param("params", params)
            .update()
    }

    private fun feedItemIdOf(sourceKey: String): Long = jdbcClient.sql(
        """
        SELECT f.id FROM hotdeal_feed_item f JOIN hotdeal_item i ON i.id = f.hotdeal_item_id
        WHERE i.source_key = :key
        """,
    ).param("key", sourceKey).query(Long::class.java).single()

    @Test
    fun `임계치 이상인 최근 추천을 점수순으로 보내고 다시 보내지 않는다`() {
        // given
        val keys = mutableListOf<String>()
        val message = slot<NotifyMessage>()
        every { router.dispatchConfirmed("hotdeal", "recommend", capture(keys), capture(message)) } returns true

        // when
        val first = notifyFacade.notifyRecommendations()
        val second = notifyFacade.notifyRecommendations()

        // then — 6시간이 지난 3번은 제외
        assertThat(first).isEqualTo(2)
        assertThat(second).isZero()
        assertThat(keys).containsExactly("hotdeal:${feedItemIdOf("1")}", "hotdeal:${feedItemIdOf("2")}")
        assertThat(message.captured.text).contains("모니터 27인치", "관심 매칭(모니터)", "원문")
    }

    @Test
    fun `하루 상한까지만 보낸다`() {
        // given
        updateSettings(
            """{"enabled": true, "score_threshold": 5.0, "daily_limit": 1, "quiet_start": "00:00", "quiet_end": "00:00"}""",
        )

        // when
        val sent = notifyFacade.notifyRecommendations() + notifyFacade.notifyRecommendations()

        // then
        assertThat(sent).isEqualTo(1)
    }

    @Test
    fun `꺼져 있으면 보내지 않는다`() {
        // given
        updateSettings("""{"enabled": false}""")

        // when
        val sent = notifyFacade.notifyRecommendations()

        // then
        assertThat(sent).isZero()
        verify(exactly = 0) { router.dispatchConfirmed(any(), any(), any(), any()) }
    }

    @Test
    fun `웹에서 피드백한 항목은 보내지 않는다`() {
        // given
        feedbackService.changeFeedback(feedItemIdOf("1"), FeedbackKindEnum.LIKE)

        // when
        notifyFacade.notifyRecommendations()

        // then
        verify(exactly = 0) { router.dispatchConfirmed(any(), any(), "hotdeal:${feedItemIdOf("1")}", any()) }
    }

    @Test
    fun `발송 실패한 항목은 기록하지 않고 다음 주기에 다시 보낸다`() {
        // given
        every { router.dispatchConfirmed(any(), any(), any(), any()) } returns false
        notifyFacade.notifyRecommendations()
        every { router.dispatchConfirmed(any(), any(), any(), any()) } returns true

        // when
        val sent = notifyFacade.notifyRecommendations()

        // then
        assertThat(sent).isEqualTo(2)
    }
}
