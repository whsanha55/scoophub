package com.scoophub.core.auth

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal

/** 인증된 사용자. `/api/auth/me` 응답 data 로도 쓰인다 (`{email, is_super}`). */
data class AuthUser(val email: String, val isSuper: Boolean)

/** legacy `get_current_user` 대응 — 비로그인 401 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("isAuthenticated()")
annotation class LoginRequired

/** legacy `get_super_user` 대응 — 비로그인 401, 비-super 403 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasRole('SUPER')")
annotation class SuperOnly

/** 컨트롤러 파라미터에서 현재 사용자 주입 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@AuthenticationPrincipal
annotation class CurrentUser
