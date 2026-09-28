package com.scoophub.external.google.dto

/** Google userinfo 엔드포인트 응답 중 사용하는 필드만 */
data class GoogleUserInfo(val email: String?, val name: String?)
