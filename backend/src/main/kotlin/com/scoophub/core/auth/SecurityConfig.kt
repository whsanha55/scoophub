package com.scoophub.core.auth

import com.scoophub.config.ScoophubProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter

/**
 * legacy 는 엔드포인트별 의존성(`get_current_user` / `get_super_user`)으로만 인증한다.
 * 따라서 URL 은 전부 열어두고 권한은 메서드 보안(@LoginRequired / @SuperOnly)으로 건다.
 * 토큰이 잘못돼도 공개 엔드포인트는 통과해야 한다 — 만료 쿠키를 가진 UI 가 깨지지 않도록.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val jwtService: JwtService,
    private val props: ScoophubProperties,
) {
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    init {
        if (props.auth.jwtSecret == DEFAULT_JWT_SECRET) {
            log.warn("JWT_SECRET is insecure (default) — set a strong random JWT_SECRET in production")
        }
        if (props.auth.bypass) {
            log.warn("AUTH_BYPASS is ON — all auth checks skipped (local dev only, NEVER enable in production)")
        }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .cors { }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .exceptionHandling {
            it.authenticationEntryPoint { request, response, _ ->
                val detail = request.getAttribute(ATTR_AUTH_ERROR) as? String ?: "not authenticated"
                response.writeDetail(HttpServletResponse.SC_UNAUTHORIZED, detail)
            }
            it.accessDeniedHandler { _, response, _ ->
                response.writeDetail(HttpServletResponse.SC_FORBIDDEN, "super user only")
            }
        }
        .addFilterBefore(BearerTokenFilter(), AnonymousAuthenticationFilter::class.java)
        .build()

    /** Bearer 토큰 → SecurityContext. 실패해도 요청은 막지 않고, 인증이 필요한 곳에서 401 사유로 쓴다. */
    private inner class BearerTokenFilter : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            val user = if (props.auth.bypass) BYPASS_USER else resolveUser(request)
            if (user != null) {
                val authorities = if (user.isSuper) listOf(SimpleGrantedAuthority("ROLE_SUPER")) else emptyList()
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)
            }
            chain.doFilter(request, response)
        }

        private fun resolveUser(request: HttpServletRequest): AuthUser? {
            val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
            if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
            val token = header.substring(BEARER_PREFIX.length).trim().ifEmpty { return null }
            return jwtService.decode(token) ?: run {
                log.warn("jwt decode failed")
                request.setAttribute(ATTR_AUTH_ERROR, "invalid or expired token")
                null
            }
        }
    }

    private fun HttpServletResponse.writeDetail(status: Int, detail: String) {
        this.status = status
        contentType = MediaType.APPLICATION_JSON_VALUE
        writer.write("""{"detail":"$detail"}""")
    }

    companion object {
        const val DEFAULT_JWT_SECRET = "dev-secret-change-me-dev-secret-change-me"
        private const val BEARER_PREFIX = "Bearer "
        private const val ATTR_AUTH_ERROR = "scoophub.authError"
        private val BYPASS_USER = AuthUser(email = "dev@local", isSuper = true)
    }
}
