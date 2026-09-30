package com.scoophub.external.alpaca

import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.news.service.NewsArticleService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

@Component
@DependsOnDatabaseInitialization
class AlpacaNewsStream(
    private val props: ScoophubProperties,
    private val mapper: JsonMapper,
    private val decoder: AlpacaNewsDecoder,
    private val articles: NewsArticleService,
    private val router: NotifyRouter,
    private val clock: Clock,
    private val connector: AlpacaSocketConnector,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "alpaca-monitor").apply {
            isDaemon =
                true
        }
    }
    private val alerts = Executors.newSingleThreadExecutor { task ->
        Thread(task, "alpaca-alert").apply {
            isDaemon =
                true
        }
    }

    @Volatile private var running = false
    private var socket: WebSocket? = null
    private var connecting = false
    private var subscribed = false
    private var reconnectAt = Instant.MIN
    private var handshakeAt: Instant? = null
    private var pingAt: Instant? = null
    private var lastPingAt = Instant.MIN
    private var backoffSeconds = 1L
    private var disconnectedAt: Instant? = null
    private var outageNotified = false
    private var alertInFlight = false
    private var nextAlertAt = Instant.MIN
    private var recovery: Pair<Instant, Long>? = null

    @PostConstruct
    fun start() {
        if (!props.alpaca.streamEnabled) return
        require(props.alpaca.apiKey.isNotBlank() && props.alpaca.apiSecret.isNotBlank()) {
            "Alpaca credentials required when stream is enabled"
        }
        running = true
        executor.scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
                log.error { "Alpaca monitor failed: ${e.javaClass.simpleName}" }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    @PreDestroy
    @Synchronized
    fun stop() {
        running = false
        socket?.abort()
        socket = null
        executor.shutdownNow()
        alerts.shutdownNow()
    }

    @Synchronized
    private fun tick() {
        if (!running) return
        val now = clock.instant()
        val current = socket
        val handshake = handshakeAt
        val ping = pingAt
        if (current != null && (
                (!subscribed && handshake != null && now >= handshake.plusSeconds(10)) ||
                    (ping != null && now >= ping.plusSeconds(10))
                )
        ) {
            disconnect(current)
        } else if (current != null && subscribed && ping == null && now >= lastPingAt.plusSeconds(30)) {
            pingAt = now
            lastPingAt = now
            current.sendPing(ByteBuffer.wrap("news-heartbeat".toByteArray())).whenComplete { _, error ->
                if (error != null) disconnect(current)
            }
        }
        if (socket == null && !connecting && now >= reconnectAt) connect()
        sendHealthAlert(now)
    }

    private fun connect() {
        connecting = true
        connector.connect(props.alpaca.streamUrl, Listener())
            .whenComplete { opened, error ->
                synchronized(this) {
                    connecting = false
                    if (!running) {
                        opened?.abort()
                    } else if (error != null) {
                        disconnect(null)
                    }
                }
            }
    }

    @Synchronized
    private fun disconnect(failed: WebSocket?) {
        if (!running || (failed != null && socket !== failed)) return
        socket?.abort()
        socket = null
        subscribed = false
        pingAt = null
        handshakeAt = null
        if (disconnectedAt == null) {
            disconnectedAt = clock.instant()
            log.warn { "Alpaca news disconnected" }
        }
        reconnectAt = clock.instant().plusSeconds(backoffSeconds)
        backoffSeconds = (backoffSeconds * 2).coerceAtMost(60)
    }

    @Synchronized
    private fun markSubscribed(current: WebSocket) {
        if (socket !== current || !running) return
        subscribed = true
        handshakeAt = null
        lastPingAt = clock.instant()
        backoffSeconds = 1
        val outage = disconnectedAt
        if (outage != null) {
            val minutes = Duration.between(outage, clock.instant()).toMinutes()
            log.info { "Alpaca news recovered: downtimeMinutes=$minutes" }
            if (outageNotified) recovery = outage to minutes
        }
        disconnectedAt = null
        outageNotified = false
    }

    private fun sendHealthAlert(now: Instant) {
        if (alertInFlight || now < nextAlertAt) return
        val restored = recovery
        val outage = disconnectedAt
        val message = when {
            restored != null -> "뉴스 소켓 복구 (끊긴 시간 ${restored.second}분)"

            outage != null && !outageNotified && now >= outage.plusSeconds(
                300,
            ) -> "뉴스 소켓 끊김 (5분 이상). 끊긴 동안의 뉴스는 수집되지 않습니다."

            else -> return
        }
        val startedAt = restored?.first ?: requireNotNull(outage)
        val kind = if (restored == null) "down" else "recovered"
        alertInFlight = true
        nextAlertAt = now.plusSeconds(60)
        alerts.execute {
            val sent = runCatching {
                router.dispatchConfirmed(
                    "news",
                    "alpaca",
                    "news:socket:$kind:${startedAt.epochSecond}",
                    NotifyMessage(message),
                )
            }.getOrDefault(false)
            synchronized(this) {
                alertInFlight = false
                if (sent) {
                    if (restored != null && recovery == restored) {
                        recovery = null
                    } else if (disconnectedAt == startedAt) {
                        outageNotified = true
                    } else if (restored ==
                        null
                    ) {
                        recovery = startedAt to Duration.between(startedAt, clock.instant()).toMinutes()
                    }
                    nextAlertAt = Instant.MIN
                }
            }
        }
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            synchronized(this@AlpacaNewsStream) {
                if (!running) {
                    webSocket.abort()
                    return
                }
                socket = webSocket
                handshakeAt = clock.instant()
            }
            send(webSocket, mapOf("action" to "auth", "key" to props.alpaca.apiKey, "secret" to props.alpaca.apiSecret))
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                try {
                    for (node in mapper.readTree(buffer.toString())) {
                        when (node.path("T").asText()) {
                            "success" -> if (node.path("msg").asText() ==
                                "authenticated"
                            ) {
                                send(webSocket, mapOf("action" to "subscribe", "news" to listOf("*")))
                            }

                            "subscription" -> if (node.path("news").any {
                                    it.asText() == "*"
                                }
                            ) {
                                markSubscribed(webSocket)
                            }

                            "n" -> articles.receive(decoder.decode(node))

                            "error" -> {
                                log.warn { "Alpaca stream error: code=${node.path("code").asInt()}" }
                                disconnect(webSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error { "Alpaca message persistence/decoding failed: type=${e.javaClass.simpleName}" }
                    disconnect(webSocket)
                } finally {
                    buffer.setLength(0)
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            synchronized(this@AlpacaNewsStream) {
                if (socket === webSocket && message == ByteBuffer.wrap("news-heartbeat".toByteArray())) pingAt = null
            }
            webSocket.request(1)
            return null
        }

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            webSocket.request(1)
            return webSocket.sendPong(message)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            disconnect(webSocket)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            disconnect(webSocket)
        }

        private fun send(webSocket: WebSocket, value: Map<String, Any>) {
            webSocket.sendText(mapper.writeValueAsString(value), true).whenComplete { _, error ->
                if (error != null) disconnect(webSocket)
            }
        }
    }
}
