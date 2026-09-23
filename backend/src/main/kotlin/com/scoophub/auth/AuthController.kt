package com.scoophub.auth

import com.scoophub.config.ScoophubProperties
import com.scoophub.core.api.ApiResponse
import com.scoophub.core.auth.AuthUser
import com.scoophub.core.auth.CurrentUser
import com.scoophub.core.auth.JwtService
import com.scoophub.core.auth.LoginRequired
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

@Tag(name = "Auth", description = "Google OAuth 인증 API")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val props: ScoophubProperties,
    private val googleOAuthClient: GoogleOAuthClient,
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    @Operation(
        summary = "Google OAuth 로그인 시작",
        description = "Google 동의 화면으로 리다이렉트합니다. state는 HttpOnly 쿠키로 보관됩니다.",
    )
    @GetMapping("/login")
    fun login(): ResponseEntity<Void> {
        val state = newState()
        val cookie = ResponseCookie.from(STATE_COOKIE, state)
            .httpOnly(true)
            .sameSite("Lax")
            .maxAge(Duration.ofMinutes(10))
            .path("/")
            .secure(!props.auth.oauthRedirectUri.startsWith("http://localhost"))
            .build()
        log.info("oauth login started, state set")
        return redirect(googleOAuthClient.authorizeUrl(state), cookie)
    }

    @Operation(
        summary = "Google OAuth 콜백",
        description = """Google이 리다이렉트한 code/state를 처리합니다.

1. state 쿠키 검증
2. code → access token → userinfo
3. ALLOWED_EMAILS 체크 → 미포함 시 403
4. users upsert (SUPER_EMAILS 매칭 시 is_super=TRUE)
5. JWT 발급 후 AUTH_REDIRECT_URL?token=... 로 리다이렉트""",
    )
    @GetMapping("/callback")
    fun callback(
        @RequestParam code: String,
        @RequestParam state: String,
        @CookieValue(STATE_COOKIE, required = false) cookieState: String?,
    ): ResponseEntity<Void> {
        if (cookieState == null || cookieState != state) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid oauth state")
        }

        val userInfo = try {
            googleOAuthClient.exchangeCode(code)
        } catch (e: Exception) {
            log.warn("oauth code exchange failed: {}", e.toString())
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "oauth provider error")
        }
        val email = userInfo.email?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "no email in userinfo")

        if (email !in props.auth.allowedEmails) {
            log.warn("denied login: {}", email)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "email not allowed")
        }

        val isSuper = email in props.auth.superEmails
        userRepository.upsert(email, userInfo.name, isSuper)
        val token = jwtService.create(email, isSuper)
        log.info("login success: {} (super={})", email, isSuper)

        val location = UriComponentsBuilder.fromUriString(props.auth.redirectUrl)
            .queryParam("token", token)
            .toUriString()
        return redirect(location, ResponseCookie.from(STATE_COOKIE, "").maxAge(0).path("/").build())
    }

    @Operation(summary = "내 정보 조회", description = "JWT로 인증된 현재 사용자 정보를 반환합니다.")
    @LoginRequired
    @GetMapping("/me")
    fun me(@CurrentUser user: AuthUser): ApiResponse<AuthUser> = ApiResponse.ok(user)

    /** Python `secrets.token_urlsafe(32)` 동일 */
    private fun newState(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Starlette `RedirectResponse` 기본값과 같은 307 */
    private fun redirect(location: String, cookie: ResponseCookie): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
            .header(HttpHeaders.LOCATION, location)
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .build()

    companion object {
        private const val STATE_COOKIE = "oauth_state"
    }
}
