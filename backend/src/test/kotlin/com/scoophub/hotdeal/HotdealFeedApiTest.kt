package com.scoophub.hotdeal

import com.scoophub.TestcontainersConfiguration
import com.scoophub.global.auth.JwtService
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.service.HotdealScoringService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HotdealFeedApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val scoringService: HotdealScoringService,
    private val clock: Clock,
    jdbcClient: JdbcClient,
    itemService: HotdealItemService,
    profileRepository: HotdealInterestProfileRepository,
) {
    private val fixtures = HotdealTestFixtures(jdbcClient, itemService, profileRepository)
    private val superBearer by lazy { "Bearer ${jwtService.create("admin@test.com", true)}" }
    private val userBearer by lazy { "Bearer ${jwtService.create("user@test.com", false)}" }

    @BeforeEach
    fun setUp() {
        fixtures.clean()
        fixtures.saveEnoughInterests()
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "SSD", 2.0)
        fixtures.saveProfile(ProfileKindEnum.KEYWORD, "모니터", 3.0)
        val now = clock.instant()
        fixtures.saveItem("1", "SSD 특가", votes = 20, itemAt = now.minusSeconds(3600))
        fixtures.saveItem("2", "모니터 27인치", votes = 0, itemAt = now.minusSeconds(60))
        scoringService.evaluateRecentItems()
    }

    @Test
    fun `추천순은 점수 내림차순으로 추천 근거와 함께 반환한다`() {
        mockMvc.get("/api/hotdeal/feed") {
            header("Authorization", superBearer)
        }.andExpect {
            status { isOk() }
            jsonPath("$.meta.total") { value(2) }
            jsonPath("$.data[0].title") { value("SSD 특가") }
            jsonPath("$.data[0].reason") { value("관심 매칭(SSD)") }
            jsonPath("$.data[0].matched_terms[0].value") { value("SSD") }
            jsonPath("$.data[0].mall") { value("지마켓") }
            jsonPath("$.data[0].price") { value(10000) }
            jsonPath("$.data[0].shipping_fee") { value("무료") }
            jsonPath("$.data[0].votes") { value(20) }
        }
    }

    @Test
    fun `최신순은 원본 게시 시각 내림차순으로 반환한다`() {
        mockMvc.get("/api/hotdeal/feed?sort=latest&limit=1&page=1") {
            header("Authorization", superBearer)
        }.andExpect {
            status { isOk() }
            jsonPath("$.meta.total") { value(2) }
            jsonPath("$.meta.returned") { value(1) }
            jsonPath("$.data[0].title") { value("모니터 27인치") }
        }
    }

    @Test
    fun `잘못된 정렬 값은 400`() {
        mockMvc.get("/api/hotdeal/feed?sort=random") {
            header("Authorization", superBearer)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `비로그인은 401, 일반 사용자는 403`() {
        mockMvc.get("/api/hotdeal/feed").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/hotdeal/feed") {
            header("Authorization", userBearer)
        }.andExpect { status { isForbidden() } }
    }
}
