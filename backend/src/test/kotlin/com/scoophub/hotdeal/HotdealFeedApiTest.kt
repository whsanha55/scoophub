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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Clock

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HotdealFeedApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val scoringService: HotdealScoringService,
    private val clock: Clock,
    private val jdbcClient: JdbcClient,
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
        fixtures.saveItem("3", "기계식 키보드", votes = 0, itemAt = now.minusSeconds(120))
        scoringService.evaluateRecentItems()
    }

    private fun feedItemIdOf(sourceKey: String): Long = jdbcClient.sql(
        """
        SELECT f.id FROM hotdeal_feed_item f JOIN hotdeal_item i ON i.id = f.hotdeal_item_id
        WHERE i.source_key = :key
        """,
    ).param("key", sourceKey).query(Long::class.java).single()

    private fun profileIdOf(value: String): Long = jdbcClient.sql(
        "SELECT id FROM hotdeal_interest_profile WHERE value = :value",
    ).param("value", value).query(Long::class.java).single()

    private fun putFeedback(feedItemId: Long, kind: String) = mockMvc.put("/api/hotdeal/feed/$feedItemId/feedback") {
        header("Authorization", superBearer)
        contentType = MediaType.APPLICATION_JSON
        content = """{"kind": "$kind"}"""
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

    @Test
    fun `싫어요한 항목은 기본 피드에서 빠지고 숨긴 항목에서 보인다`() {
        // given
        putFeedback(feedItemIdOf("1"), "DISLIKE").andExpect {
            status { isOk() }
            jsonPath("$.data.feedback") { value("DISLIKE") }
        }

        // when / then
        mockMvc.get("/api/hotdeal/feed") { header("Authorization", superBearer) }.andExpect {
            jsonPath("$.meta.total") { value(1) }
            jsonPath("$.data[0].title") { value("모니터 27인치") }
            jsonPath("$.data[0].feedback") { doesNotExist() }
        }
        mockMvc.get("/api/hotdeal/feed?filter=hidden") { header("Authorization", superBearer) }.andExpect {
            jsonPath("$.meta.total") { value(1) }
            jsonPath("$.data[0].title") { value("SSD 특가") }
            jsonPath("$.data[0].feedback") { value("DISLIKE") }
        }
    }

    @Test
    fun `피드백을 취소하면 기본 피드로 돌아온다`() {
        // given
        val feedItemId = feedItemIdOf("1")
        putFeedback(feedItemId, "DISLIKE")

        // when
        mockMvc.delete("/api/hotdeal/feed/$feedItemId/feedback") {
            header("Authorization", superBearer)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.feedback") { doesNotExist() }
        }

        // then
        mockMvc.get("/api/hotdeal/feed") { header("Authorization", superBearer) }.andExpect {
            jsonPath("$.meta.total") { value(2) }
        }
    }

    @Test
    fun `좋아요한 항목은 추천 조건을 벗어나도 기본 피드와 좋아요 목록에 남는다`() {
        // given
        putFeedback(feedItemIdOf("2"), "LIKE")

        // when — 모니터 키워드를 지우면 즉시 재평가되어 추천 조건을 벗어난다
        mockMvc.delete("/api/hotdeal/profiles/${profileIdOf("모니터")}") {
            header("Authorization", superBearer)
        }.andExpect { status { isOk() } }

        // then
        mockMvc.get("/api/hotdeal/feed?sort=latest") { header("Authorization", superBearer) }.andExpect {
            jsonPath("$.meta.total") { value(2) }
            jsonPath("$.data[0].title") { value("모니터 27인치") }
            jsonPath("$.data[0].active") { value(false) }
            jsonPath("$.data[0].feedback") { value("LIKE") }
        }
        mockMvc.get("/api/hotdeal/feed?filter=liked") { header("Authorization", superBearer) }.andExpect {
            jsonPath("$.meta.total") { value(1) }
        }
    }

    @Test
    fun `피드백 없이 추천 조건을 벗어난 항목은 기본 피드에서 숨긴다`() {
        // when
        mockMvc.delete("/api/hotdeal/profiles/${profileIdOf("모니터")}") {
            header("Authorization", superBearer)
        }

        // then
        mockMvc.get("/api/hotdeal/feed") { header("Authorization", superBearer) }.andExpect {
            jsonPath("$.meta.total") { value(1) }
            jsonPath("$.data[0].title") { value("SSD 특가") }
        }
    }

    @Test
    fun `관심 키워드를 추가하면 기본 가중치로 등록하고 즉시 재평가한다`() {
        // when
        mockMvc.post("/api/hotdeal/profiles") {
            header("Authorization", superBearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind": "KEYWORD", "value": " 키보드 "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.value") { value("키보드") }
            jsonPath("$.data.seed_weight") { value(3.0) }
        }

        // then
        mockMvc.get("/api/hotdeal/feed?sort=latest") { header("Authorization", superBearer) }.andExpect {
            jsonPath("$.meta.total") { value(3) }
            jsonPath("$.data[1].title") { value("기계식 키보드") }
            jsonPath("$.data[1].reason") { value("관심 매칭(키보드)") }
        }
    }

    @Test
    fun `이미 있는 관심 항목은 409, 빈 값은 400`() {
        mockMvc.post("/api/hotdeal/profiles") {
            header("Authorization", superBearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind": "KEYWORD", "value": "SSD"}"""
        }.andExpect { status { isConflict() } }

        mockMvc.post("/api/hotdeal/profiles") {
            header("Authorization", superBearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind": "KEYWORD", "value": "  "}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `관심도 프로필 목록은 유효 가중치를 함께 반환한다`() {
        mockMvc.get("/api/hotdeal/profiles") { header("Authorization", superBearer) }.andExpect {
            status { isOk() }
            jsonPath("$.meta.total") { value(7) }
            jsonPath("$.data[0].effective_weight") { exists() }
        }
    }
}
