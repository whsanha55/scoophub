package com.scoophub

import com.scoophub.global.config.ScoophubProperties

/** 스프링 컨텍스트 없이 클라이언트를 만들 때 쓰는 기본 설정. 외부 API 주소는 운영 기본값이다 */
fun scoophubProperties() = ScoophubProperties(
    enableScheduler = false,
    corsOrigins = emptyList(),
    llm = ScoophubProperties.Llm(apiUrl = "", apiKey = "", model = ""),
    auth = ScoophubProperties.Auth(
        allowedEmails = emptySet(),
        superEmails = emptySet(),
        googleClientId = "",
        googleClientSecret = "",
        jwtSecret = "",
        jwtExpireHours = 24,
        bypass = false,
        redirectUrl = "",
        oauthRedirectUri = "",
    ),
    telegram = ScoophubProperties.Telegram(botToken = "", defaultChatId = ""),
    youtubeApiKey = "",
)
