package com.scoophub.game

import com.scoophub.game.service.AbuseLimiter
import com.scoophub.game.service.DefaultAbuseLimiter
import com.scoophub.game.service.NoopAbuseLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import java.time.Clock

/** /ws/game/{code} — 순수 WebSocket (ADR-0003). 운영에서 Caddy 가 /ws* 를 이 포트로 보낸다. */
@Configuration
@EnableWebSocket
class GameWebSocketConfig(private val gameWebSocketHandler: GameWebSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game/*").setAllowedOrigins("*")
    }
}

/** 남용 방지 한도 빈. local 프로파일에서는 끈다 (ADR-0002). */
@Configuration
class AbuseLimiterConfig {
    @Bean
    @Profile("!local")
    fun defaultAbuseLimiter(clock: Clock): AbuseLimiter = DefaultAbuseLimiter(clock)

    @Bean
    @Profile("local")
    fun noopAbuseLimiter(): AbuseLimiter = NoopAbuseLimiter()
}
