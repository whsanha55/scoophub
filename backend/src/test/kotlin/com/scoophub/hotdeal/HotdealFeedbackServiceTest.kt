package com.scoophub.hotdeal

import com.scoophub.TestcontainersConfiguration
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealFeedbackLogRepository
import com.scoophub.hotdeal.repository.HotdealFeedbackRepository
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.service.HotdealFeedbackService
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.service.HotdealScoringService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HotdealFeedbackServiceTest @Autowired constructor(
    private val feedbackService: HotdealFeedbackService,
    private val scoringService: HotdealScoringService,
    private val profileRepository: HotdealInterestProfileRepository,
    private val feedbackRepository: HotdealFeedbackRepository,
    private val feedbackLogRepository: HotdealFeedbackLogRepository,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
    itemService: HotdealItemService,
) {
    private val fixtures = HotdealTestFixtures(jdbcClient, itemService, profileRepository)

    @BeforeEach
    fun clean() = fixtures.clean()

    private fun saveItem(sourceKey: String, title: String, votes: Long = 0) =
        fixtures.saveItem(sourceKey, title, votes, itemAt = clock.instant().minusSeconds(600))

    private fun feedItemIdOf(sourceKey: String): Long = jdbcClient.sql(
        """
        SELECT f.id FROM hotdeal_feed_item f JOIN hotdeal_item i ON i.id = f.hotdeal_item_id
        WHERE i.source_key = :key
        """,
    ).param("key", sourceKey).query(Long::class.java).single()

    private fun learnedOf(profileId: Long?): Double =
        profileRepository.findByIdOrNull(requireNotNull(profileId))!!.learnedWeight

    @Test
    fun `좋아요는 매칭된 관심 항목의 가중치를 올리고 피드백을 저장한다`() {
        // given
        val ssd = fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 3.0)
        saveItem("1", "SSD 특가")
        scoringService.evaluateRecentItems()
        val feedItemId = feedItemIdOf("1")

        // when
        val result = feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.LIKE)

        // then
        assertThat(result).isEqualTo(FeedbackKindEnum.LIKE)
        assertThat(learnedOf(ssd.id)).isCloseTo(0.5, within(1e-9))
        assertThat(feedbackRepository.findByFeedItemId(feedItemId)!!.kind).isEqualTo(FeedbackKindEnum.LIKE)
        assertThat(feedbackLogRepository.findAll().map { it.delta }).containsExactly(0.5)
    }

    @Test
    fun `같은 피드백을 다시 보내면 추가 학습하지 않는다`() {
        // given
        val ssd = fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 3.0)
        saveItem("1", "SSD 특가")
        scoringService.evaluateRecentItems()
        val feedItemId = feedItemIdOf("1")
        feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.LIKE)

        // when
        feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.LIKE)

        // then
        assertThat(learnedOf(ssd.id)).isCloseTo(0.5, within(1e-9))
        assertThat(feedbackLogRepository.count()).isEqualTo(1L)
    }

    @Test
    fun `좋아요에서 싫어요로 바꾸면 이전 효과를 되돌린 뒤 새 효과를 적용한다`() {
        // given
        val ssd = fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 3.0)
        saveItem("1", "SSD 특가")
        scoringService.evaluateRecentItems()
        val feedItemId = feedItemIdOf("1")
        feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.LIKE)

        // when
        feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.DISLIKE)

        // then — +0.5 를 되돌리고 -0.5 를 적용해 이번 변경의 실제 delta 는 -1.0
        assertThat(learnedOf(ssd.id)).isCloseTo(-0.5, within(1e-9))
        assertThat(feedbackLogRepository.findAll().map { it.delta }).containsExactlyInAnyOrder(0.5, -1.0)
    }

    @Test
    fun `취소하면 학습 효과를 되돌리고 피드백을 지운다`() {
        // given
        val ssd = fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 3.0)
        saveItem("1", "SSD 특가")
        scoringService.evaluateRecentItems()
        val feedItemId = feedItemIdOf("1")
        feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.DISLIKE)

        // when
        val result = feedbackService.changeFeedback(feedItemId, null)

        // then
        assertThat(result).isNull()
        assertThat(learnedOf(ssd.id)).isCloseTo(0.0, within(1e-9))
        assertThat(feedbackRepository.findByFeedItemId(feedItemId)).isNull()
    }

    @Test
    fun `여러 관심 항목이 매칭되면 변화량을 나누어 적용한다`() {
        // given
        val ssd = fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 2.0)
        val nvme = fixtures.saveProfile(ProfileKindEnum.KEYWORD, "NVMe", 2.0)
        saveItem("1", "NVMe SSD 특가")
        scoringService.evaluateRecentItems()

        // when
        feedbackService.changeFeedback(feedItemIdOf("1"), FeedbackKindEnum.LIKE)

        // then
        assertThat(learnedOf(ssd.id)).isCloseTo(0.25, within(1e-9))
        assertThat(learnedOf(nvme.id)).isCloseTo(0.25, within(1e-9))
    }

    @Test
    fun `유효 가중치 상한을 넘지 않고 취소하면 실제 적용분만 되돌린다`() {
        // given
        val ssd = fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 4.8)
        saveItem("1", "SSD 특가")
        scoringService.evaluateRecentItems()
        val feedItemId = feedItemIdOf("1")

        // when
        feedbackService.changeFeedback(feedItemId, FeedbackKindEnum.LIKE)
        val learnedAfterLike = learnedOf(ssd.id)
        feedbackService.changeFeedback(feedItemId, null)

        // then — 상한 5.0 까지 0.2 만 적용되고, 취소하면 0 으로 돌아간다
        assertThat(learnedAfterLike).isCloseTo(0.2, within(1e-9))
        assertThat(learnedOf(ssd.id)).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `매칭 항목이 없는 인기 항목 피드백도 이력 1건을 남긴다`() {
        // given — 콜드스타트(관심 0개)라 인기 노출된다
        saveItem("1", "아무 제품", votes = 30)
        scoringService.evaluateRecentItems()

        // when
        feedbackService.changeFeedback(feedItemIdOf("1"), FeedbackKindEnum.LIKE)

        // then
        val log = feedbackLogRepository.findAll().single()
        assertThat(log.profileId).isNull()
        assertThat(log.delta).isZero()
    }

    @Test
    fun `없는 피드 항목이면 404`() {
        // when
        val exception = assertThrows<ResponseStatusException> {
            feedbackService.changeFeedback(999_999L, FeedbackKindEnum.LIKE)
        }

        // then
        assertThat(exception.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
