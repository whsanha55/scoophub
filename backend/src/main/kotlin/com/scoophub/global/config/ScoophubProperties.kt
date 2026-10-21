package com.scoophub.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** legacy `app/config.py` 대응. env 이름 매핑은 application.yml 의 `scoophub.*` 참고. */
@ConfigurationProperties("scoophub")
data class ScoophubProperties(
    val enableScheduler: Boolean,
    val corsOrigins: List<String>,
    val llm: Llm,
    val auth: Auth,
    val telegram: Telegram,
    val youtubeApiKey: String,
    val alpaca: Alpaca = Alpaca(),
    val endpoints: Endpoints = Endpoints(),
) {
    /** 외부 API 주소. 운영은 기본값을 쓰고, E2E 는 스텁 서버를 가리킨다 (application-e2e.yml) */
    data class Endpoints(
        val hackerNews: String = "https://hacker-news.firebaseio.com/v0",
        val devto: String = "https://dev.to/api",
        val arxiv: String = "https://export.arxiv.org/api/query",
        val youtube: String = "https://www.googleapis.com/youtube/v3",
        val github: String = "https://github.com",
        val wttr: String = "https://wttr.in",
        val openMeteoAirQuality: String = "https://air-quality-api.open-meteo.com/v1/air-quality",
        val telegram: String = "https://api.telegram.org",
        val googleAuthorize: String = "https://accounts.google.com/o/oauth2/v2/auth",
        val googleToken: String = "https://oauth2.googleapis.com/token",
        val googleUserinfo: String = "https://www.googleapis.com/oauth2/v3/userinfo",
    )

    data class Alpaca(
        val apiKey: String = "",
        val apiSecret: String = "",
        val streamUrl: String = "wss://stream.data.alpaca.markets/v1beta1/news",
        /** Market Data API(일봉, 스냅샷, 옵션 체인) */
        val dataUrl: String = "https://data.alpaca.markets",
        /** Trading API(옵션 계약 open interest). live 키면 https://api.alpaca.markets */
        val tradingUrl: String = "https://paper-api.alpaca.markets",
        val streamEnabled: Boolean = false,
        val workerEnabled: Boolean = false,
        val burstThreshold: Int = 3,
        val burstExcludedSymbols: Set<String> = setOf("SPY", "BTCUSD"),
    )

    data class Llm(val apiUrl: String, val apiKey: String, val model: String)

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
