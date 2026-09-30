package com.scoophub.external.alpaca

import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture

@Component
class AlpacaSocketConnector {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun connect(url: String, listener: WebSocket.Listener): CompletableFuture<WebSocket> =
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), listener)
}
