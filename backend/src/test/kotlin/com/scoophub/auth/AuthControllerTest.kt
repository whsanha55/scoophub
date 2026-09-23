package com.scoophub.auth

import com.scoophub.TestcontainersConfiguration
import com.scoophub.core.auth.JwtService
import com.scoophub.core.auth.SuperOnly
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** legacy tests/test_auth.py 포팅 */
@SpringBootTest(
    properties = [
        "scoophub.auth.allowed-emails=alice@example.com,bob@example.com",
        "scoophub.auth.super-emails=alice@example.com",
        "scoophub.auth.bypass=false",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, AuthControllerTest.ProbeController::class)
class AuthControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
) {
    @MockitoBean
    private lateinit var googleOAuthClient: GoogleOAuthClient

    /** 공개 GET / super 전용 mutation 을 흉내내는 테스트용 엔드포인트 */
    @RestController
    class ProbeController {
        @GetMapping("/test/public")
        fun public() = "ok"

        @SuperOnly
        @PostMapping("/test/super")
        fun superOnly() = "ok"
    }

    @BeforeEach
    fun cleanUsers() = userRepository.deleteAllInBatch()

    private fun bearer(email: String, isSuper: Boolean) = "Bearer ${jwtService.create(email, isSuper)}"

    private fun ResultActionsDsl.detail(expected: String) = andExpect { jsonPath("$.detail") { value(expected) } }

    @Nested
    inner class Jwt {
        @Test
        fun `발급한 토큰을 다시 검증하면 email, is_super 복원`() {
            val user = jwtService.decode(jwtService.create("x@y.com", true))
            assertEquals("x@y.com", user?.email)
            assertEquals(true, user?.isSuper)
        }

        @Test
        fun `잘못된 토큰은 null`() {
            assertNull(jwtService.decode("not-a-jwt"))
        }
    }

    @Nested
    inner class RouteProtection {
        @Test
        fun `공개 GET 은 토큰 없이 200`() {
            mockMvc.get("/test/public").andExpect { status { isOk() } }
        }

        @Test
        fun `공개 GET 은 잘못된 토큰이어도 200 - 만료 쿠키 UI 보호`() {
            mockMvc.get("/test/public") { header("Authorization", "Bearer expired.or.broken") }
                .andExpect { status { isOk() } }
        }

        @Test
        fun `super 전용 - 토큰 없으면 401`() {
            mockMvc.post("/test/super").andExpect { status { isUnauthorized() } }.detail("not authenticated")
        }

        @Test
        fun `super 전용 - 잘못된 토큰이면 401`() {
            mockMvc.post("/test/super") { header("Authorization", "Bearer broken") }
                .andExpect { status { isUnauthorized() } }.detail("invalid or expired token")
        }

        @Test
        fun `super 전용 - 일반 사용자는 403`() {
            mockMvc.post("/test/super") { header("Authorization", bearer("bob@example.com", false)) }
                .andExpect { status { isForbidden() } }.detail("super user only")
        }

        @Test
        fun `super 전용 - super 사용자는 200`() {
            mockMvc.post("/test/super") { header("Authorization", bearer("alice@example.com", true)) }
                .andExpect { status { isOk() } }
        }

        @Test
        fun `API 문서는 공개`() {
            mockMvc.get("/docs").andExpect { status { is3xxRedirection() } }
            mockMvc.get("/openapi.json").andExpect { status { isOk() } }
        }

        @Test
        fun `없는 경로는 404 detail 바디`() {
            mockMvc.get("/api/nope").andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { exists() }
            }
        }
    }

    @Nested
    inner class Me {
        @Test
        fun `토큰 없으면 401`() {
            mockMvc.get("/api/auth/me").andExpect { status { isUnauthorized() } }.detail("not authenticated")
        }

        @Test
        fun `유효한 토큰이면 사용자 정보`() {
            mockMvc.get("/api/auth/me") { header("Authorization", bearer("alice@example.com", true)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.success") { value(true) }
                    jsonPath("$.data.email") { value("alice@example.com") }
                    jsonPath("$.data.is_super") { value(true) }
                    jsonPath("$.error") { value(null) }
                    jsonPath("$.meta.requested_at") { exists() }
                }
        }
    }

    @Nested
    inner class OAuthFlow {
        @Test
        fun `login 은 Google 로 307 리다이렉트하고 state 쿠키 설정`() {
            given(googleOAuthClient.authorizeUrl(org.mockito.ArgumentMatchers.anyString()))
                .willReturn("https://accounts.google.com/o/oauth2/v2/auth?state=s")

            mockMvc.get("/api/auth/login").andExpect {
                status { isTemporaryRedirect() }
                header { string("Location", startsWith("https://accounts.google.com")) }
                header { string("Set-Cookie", containsString("oauth_state=")) }
                header { string("Set-Cookie", containsString("HttpOnly")) }
            }
        }

        @Test
        fun `state 불일치면 400`() {
            mockMvc.get("/api/auth/callback?code=c&state=x") { cookie(jakarta.servlet.http.Cookie("oauth_state", "y")) }
                .andExpect { status { isBadRequest() } }.detail("invalid oauth state")
        }

        @Test
        fun `허용되지 않은 이메일은 403`() {
            given(googleOAuthClient.exchangeCode("c"))
                .willReturn(GoogleOAuthClient.UserInfo("stranger@example.com", "Stranger"))

            callback().andExpect { status { isForbidden() } }.detail("email not allowed")
            assertNull(userRepository.findByEmail("stranger@example.com"))
        }

        @Test
        fun `provider 오류면 502`() {
            given(googleOAuthClient.exchangeCode("c")).willThrow(RestClientException("boom"))

            callback().andExpect { status { isBadGateway() } }.detail("oauth provider error")
        }

        @Test
        fun `성공하면 토큰과 함께 리다이렉트, super 사용자 upsert`() {
            given(googleOAuthClient.exchangeCode("c"))
                .willReturn(GoogleOAuthClient.UserInfo("alice@example.com", "Alice"))

            val location = callback().andExpect { status { isTemporaryRedirect() } }
                .andReturn().response.getHeader("Location")!!

            assertTrue(location.startsWith("http://localhost:3000/auth/callback?token="))
            assertEquals("alice@example.com", jwtService.decode(location.substringAfter("token="))?.email)
            val user = assertNotNull(userRepository.findByEmail("alice@example.com"))
            assertEquals("Alice", user.name)
            assertTrue(user.isSuper)
        }

        @Test
        fun `일반 사용자 upsert 는 is_super false, 재로그인해도 1건`() {
            given(googleOAuthClient.exchangeCode("c"))
                .willReturn(GoogleOAuthClient.UserInfo("bob@example.com", "Bob"))
            callback()
            given(googleOAuthClient.exchangeCode("c"))
                .willReturn(GoogleOAuthClient.UserInfo("bob@example.com", "Bob2"))
            callback()

            val user = assertNotNull(userRepository.findByEmail("bob@example.com"))
            assertEquals("Bob2", user.name)
            assertEquals(false, user.isSuper)
            assertEquals(1, userRepository.count())
        }

        private fun callback() =
            mockMvc.get("/api/auth/callback?code=c&state=s") { cookie(jakarta.servlet.http.Cookie("oauth_state", "s")) }
    }
}
