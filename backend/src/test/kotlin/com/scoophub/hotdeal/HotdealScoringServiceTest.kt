package com.scoophub.hotdeal

import com.scoophub.TestcontainersConfiguration
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealFeedItemRepository
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.service.HotdealScoringService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.time.Clock

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HotdealScoringServiceTest @Autowired constructor(
    private val scoringService: HotdealScoringService,
    private val feedItemRepository: HotdealFeedItemRepository,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
    jdbcClient: JdbcClient,
    itemService: HotdealItemService,
    profileRepository: HotdealInterestProfileRepository,
) {
    private val fixtures = HotdealTestFixtures(jdbcClient, itemService, profileRepository)

    @BeforeEach
    fun clean() = fixtures.clean()

    private fun saveItem(sourceKey: String, title: String, votes: Long) =
        fixtures.saveItem(sourceKey, title, votes, itemAt = clock.instant().minusSeconds(600))

    /** (popular, title) 목록. 지연 로딩 item 을 읽기 위해 트랜잭션 안에서 조회한다 */
    private fun feedTitles(): List<Pair<Boolean, String>> = requireNotNull(
        transactionTemplate.execute { feedItemRepository.findAll().map { it.popular to it.item.title } },
    )

    @Test
    fun `매칭된 키워드 가중치 합이 임계치를 넘으면 추천 항목을 만든다`() {
        // given
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 2.0)
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "래칫", 1.5)
        saveItem(sourceKey = "1", title = "WD SSD 2TB 특가", votes = 10)

        // when
        scoringService.evaluateRecentItems()

        // then
        val feedItem = feedItemRepository.findAll().single()
        assertThat(feedItem.popular).isFalse()
        // score = 키워드 2.0 + 인기 보너스(10 * 0.1)
        assertThat(feedItem.score).isCloseTo(3.0, Percentage.withPercentage(1.0))
        assertThat(feedItem.matchedTerms["reason"].asText()).isEqualTo("관심 매칭(SSD)")
        val terms: Iterable<JsonNode> = feedItem.matchedTerms["terms"]
        assertThat(terms.map { it["value"].asText() }).containsExactly("SSD")
    }

    @Test
    fun `매칭된 키워드가 없으면 추천하지 않는다`() {
        // given
        fixtures.saveEnoughInterests("SSD")
        saveItem(sourceKey = "2", title = "전기밥솥 특가", votes = 10)

        // when
        scoringService.evaluateRecentItems()

        // then
        assertThat(feedItemRepository.findAll()).isEmpty()
    }

    @Test
    fun `매칭이 있어도 총점이 임계치 미만이면 추천하지 않는다`() {
        // given
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 1.0)
        fixtures.saveEnoughInterests("밥솥", "SSD")
        saveItem(sourceKey = "3", title = "SSD 특가", votes = 0)

        // when
        scoringService.evaluateRecentItems()

        // then
        assertThat(feedItemRepository.findAll()).isEmpty()
    }

    @Test
    fun `관심 매칭 없이 소스 가중치만으로는 일반 추천이 되지 않는다`() {
        // given
        fixtures.saveProfile(ProfileKindEnum.SOURCE, "ZOD", 5.0)
        saveItem(sourceKey = "4", title = "아무렇게나 좋은 제품", votes = 100)

        // when
        scoringService.evaluateRecentItems()

        // then — 콜드스타트(관심 0개)라 인기 노출로만 생성된다
        val feedItem = feedItemRepository.findAll().single()
        assertThat(feedItem.popular).isTrue()
        assertThat(feedItem.matchedTerms["reason"].asText()).isEqualTo("인기 항목")
    }

    @Test
    fun `관심 항목이 부족하면 인기 상위를 함께 노출한다`() {
        // given
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 1.0)
        saveItem(sourceKey = "10", title = "SSD 특가", votes = 5)
        saveItem(sourceKey = "11", title = "무관심 제품 A", votes = 50)
        saveItem(sourceKey = "12", title = "무관심 제품 B", votes = 30)

        // when
        scoringService.evaluateRecentItems()

        // then — 관심 1개(< 5)라 세 항목 모두 인기 노출된다
        assertThat(feedTitles()).containsExactlyInAnyOrder(
            true to "SSD 특가",
            true to "무관심 제품 A",
            true to "무관심 제품 B",
        )
    }

    @Test
    fun `관심 항목이 충분하면 인기 노출을 만들지 않는다`() {
        // given
        fixtures.saveEnoughInterests("SSD")
        saveItem(sourceKey = "20", title = "무관심 제품", votes = 100)

        // when
        scoringService.evaluateRecentItems()

        // then
        assertThat(feedItemRepository.findAll()).isEmpty()
    }

    @Test
    fun `신선도 기간이 지난 원본은 평가하지 않는다`() {
        // given
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 5.0)
        fixtures.saveItem("40", "SSD 특가", votes = 0, itemAt = clock.instant().minusSeconds(73 * 3600))

        // when
        scoringService.evaluateRecentItems()

        // then
        assertThat(feedItemRepository.findAll()).isEmpty()
    }

    @Test
    fun `재평가에서 원본 메타데이터가 바뀌면 기존 항목 점수를 갱신한다`() {
        // given
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 2.0)
        saveItem(sourceKey = "30", title = "SSD 특가", votes = 10)
        scoringService.evaluateRecentItems()
        val first = feedItemRepository.findAll().single()

        // when
        saveItem(sourceKey = "30", title = "SSD 특가", votes = 50)
        scoringService.evaluateRecentItems()

        // then — 인기 보너스 상한(2.0)까지 반영해 갱신하고 최초 추천 시각은 유지한다
        val after = feedItemRepository.findAll().single()
        assertThat(after.id).isEqualTo(first.id)
        assertThat(after.score).isCloseTo(4.0, Percentage.withPercentage(1.0))
        assertThat(after.evaluatedAt).isAfterOrEqualTo(first.evaluatedAt)
        assertThat(after.recommendedAt).isEqualTo(first.recommendedAt)
    }
}
