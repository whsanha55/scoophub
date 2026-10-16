package com.scoophub.game.config

import com.scoophub.game.GameWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/** /ws/game/{code} — 순수 WebSocket (ADR-0003). 운영에서 Caddy 가 /ws* 를 이 포트로 보낸다. */
@Configuration
@EnableWebSocket
class GameWebSocketConfig(private val gameWebSocketHandler: GameWebSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game/*").setAllowedOrigins("*")
    }
}
