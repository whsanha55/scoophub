package com.scoophub.hotdeal

import com.ninjasquad.springmockk.MockkBean
import com.scoophub.TestcontainersConfiguration
import com.scoophub.external.llm.LlmClient
import com.scoophub.global.auth.JwtService
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.facade.HotdealJudgmentFacade
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.service.HotdealFeedbackService
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.service.HotdealScoringService
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Clock

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HotdealJudgmentAndSettingsTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val scoringService: HotdealScoringService,
    private val judgmentFacade: HotdealJudgmentFacade,
    private val feedbackService: HotdealFeedbackService,
    private val profileRepository: HotdealInterestProfileRepository,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
    itemService: HotdealItemService,
) {
    private val fixtures = HotdealTestFixtures(jdbcClient, itemService, profileRepository)
    private val bearer by lazy { "Bearer ${jwtService.create("admin@test.com", true)}" }
    private var ramProfileId = 0L

    @MockkBean
    private lateinit var llm: LlmClient

    @BeforeEach
    fun setUp() {
        fixtures.clean()
        fixtures.saveEnoughInterests()
        ramProfileId = requireNotNull(fixtures.saveProfile(ProfileKindEnum.KEYWORD, "램", 3.0).id)
        val now = clock.instant()
        fixtures.saveItem("1", "DDR5 램 32GB", votes = 0, itemAt = now.minusSeconds(60))
        fixtures.saveItem("2", "LED 램프 특가", votes = 0, itemAt = now.minusSeconds(120))
        fixtures.saveItem("3", "중고 램 16GB", votes = 0, itemAt = now.minusSeconds(180))
        scoringService.evaluateRecentItems()
    }

    private fun feedItemIdOf(sourceKey: String): Long = jdbcClient.sql(
        """
        SELECT f.id FROM hotdeal_feed_item f JOIN hotdeal_item i ON i.id = f.hotdeal_item_id
        WHERE i.source_key = :key
        """,
    ).param("key", sourceKey).query(Long::class.java).single()

    private fun llmAnswers(vararg relevantBySourceKey: Pair<String, Boolean>) {
        val json = relevantBySourceKey.joinToString(",", "[", "]") { (key, relevant) ->
            """{"id": ${feedItemIdOf(key)}, "relevant": $relevant, "excluded": false, "reason": "판정 $key"}"""
        }
        every { llm.chat(any(), any()) } returns json
    }

    private fun feedTitles(filter: String): List<String> {
        val body = mockMvc.get("/api/hotdeal/feed?sort=latest&filter=$filter") {
            header("Authorization", bearer)
        }.andReturn().response.contentAsString
        return Regex("\"title\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `관련 없다고 판정된 항목은 기본 피드에서 빠지고 숨긴 항목에서 사유와 함께 보인다`() {
        // given
        llmAnswers("1" to true, "2" to false, "3" to true)

        // when
        judgmentFacade.judgePending()

        // then
        assertThat(feedTitles("default")).containsExactly("DDR5 램 32GB", "중고 램 16GB")
        mockMvc.get("/api/hotdeal/feed?filter=hidden") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.meta.total") { value(1) }
            jsonPath("$.data[0].title") { value("LED 램프 특가") }
            jsonPath("$.data[0].hidden_reason") { value("판정 2") }
        }
    }

    @Test
    fun `판정 입력이 바뀌지 않으면 다시 판정하지 않는다`() {
        // given
        llmAnswers("1" to true, "2" to true, "3" to true)
        judgmentFacade.judgePending()

        // when
        scoringService.evaluateRecentItems()
        val judged = judgmentFacade.judgePending()

        // then
        assertThat(judged).isZero()
        verify(exactly = 1) { llm.chat(any(), any()) }
    }

    @Test
    fun `제외 키워드를 추가하면 LLM 이 실패해도 키워드 매칭으로 바로 숨긴다`() {
        // given
        every { llm.chat(any(), any()) } throws IllegalStateException("llm down")

        // when
        mockMvc.post("/api/hotdeal/profiles") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind": "EXCLUDE", "value": "중고"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.seed_weight") { value(0.0) }
        }

        // then
        assertThat(feedTitles("default")).containsExactly("DDR5 램 32GB", "LED 램프 특가")
        mockMvc.get("/api/hotdeal/feed?filter=hidden") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.data[0].title") { value("중고 램 16GB") }
            jsonPath("$.data[0].hidden_reason") { value("제외 키워드(중고)") }
        }
    }

    @Test
    fun `수동 가중치를 낮추면 즉시 재평가되어 임계치 아래 항목이 빠진다`() {
        // given
        every { llm.chat(any(), any()) } throws IllegalStateException("llm down")

        // when
        mockMvc.patch("/api/hotdeal/profiles/$ramProfileId") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"seed_weight": 1.0}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.effective_weight") { value(1.0) }
        }

        // then
        assertThat(feedTitles("default")).isEmpty()
    }

    @Test
    fun `수동 가중치는 0~5 범위만 받는다`() {
        mockMvc.patch("/api/hotdeal/profiles/$ramProfileId") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"seed_weight": 6.0}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `학습값을 초기화하면 이후 피드백을 취소해도 이미 사라진 효과를 다시 빼지 않는다`() {
        // given
        every { llm.chat(any(), any()) } throws IllegalStateException("llm down")
        val feedItemId = feedItemIdOf("1")
        feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.LIKE)

        // when
        mockMvc.post("/api/hotdeal/profiles/reset-learned") {
            header("Authorization", bearer)
        }.andExpect { status { isOk() } }
        feedbackService.changeFeedback(feedItemId, null)

        // then
        val learned = profileRepository.findByIdOrNull(ramProfileId)!!.learnedWeight
        assertThat(learned).isCloseTo(0.0, within(1e-9))
    }
}
