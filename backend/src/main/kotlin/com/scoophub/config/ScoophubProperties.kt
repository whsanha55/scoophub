package com.scoophub.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** legacy `app/config.py` 대응. env 이름 매핑은 application.yml 의 `scoophub.*` 참고. */
@ConfigurationProperties("scoophub")
data class ScoophubProperties(
    val enableScheduler: Boolean,
    val corsOrigins: List<String>,
    val llm: Llm,
    val auth: Auth,
    val telegram: Telegram,
    val producthuntToken: String,
    val youtubeApiKey: String,
) {
    data class Llm(
        val apiUrl: String,
        val apiKey: String,
        val model: String,
    )

    data class Auth(
        val allowedEmails: Set<String>,
        val superEmails: Set<String>,
        val googleClientId: String,
        val googleClientSecret: String,
        val jwtSecret: String,
        val jwtExpireHours: Long,
        /** 로컬 전용: true 면 인증 건너뛰고 super user 로 통과 */
        val bypass: Boolean,
        val redirectUrl: String,
        val oauthRedirectUri: String,
    )

    data class Telegram(
        val botToken: String,
        /** 자동 토픽 프로비저닝 기본 chat_id. 빈 값이면 자동 라우트 생성 안 함 */
        val defaultChatId: String,
    )
}
