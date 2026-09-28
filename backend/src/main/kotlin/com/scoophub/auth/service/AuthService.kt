package com.scoophub.auth.service

import com.scoophub.external.google.client.GoogleOAuthClient
import com.scoophub.global.auth.JwtService
import com.scoophub.global.config.ScoophubProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

private val log = KotlinLogging.logger {}

/**
 * OAuth 콜백 유스케이스. 외부 호출(트랜잭션 밖) → 허용 체크 → users upsert → JWT 발급.
 * 에러는 legacy 문구 그대로 `ResponseStatusException`으로 던진다 (LOCAL.md 예외).
 */
@Service
class AuthService(
    private val googleOAuthClient: GoogleOAuthClient,
    private val userService: UserService,
    private val jwtService: JwtService,
    private val props: ScoophubProperties,
) {

    /** code 교환 후 검증·저장까지 마치고 JWT 토큰을 반환한다 */
    fun completeLogin(code: String): String {
        val userInfo = try {
            googleOAuthClient.exchangeCode(code)
        } catch (e: Exception) {
            log.warn { "oauth code exchange failed: $e" }
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "oauth provider error")
        }
        val email = userInfo.email?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "no email in userinfo")

        if (email !in props.auth.allowedEmails) {
            log.warn { "denied login: $email" }
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "email not allowed")
        }

        val isSuper = email in props.auth.superEmails
        userService.upsert(email, userInfo.name, isSuper)
        val token = jwtService.create(email, isSuper)
        log.info { "login success: $email (super=$isSuper)" }
        return token
    }
}
