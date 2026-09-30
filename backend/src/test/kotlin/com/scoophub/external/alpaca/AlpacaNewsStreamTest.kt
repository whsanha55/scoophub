package com.scoophub.external.alpaca

import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.news.service.NewsArticleService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class AlpacaNewsStreamTest {
    private class MutableClock(@Volatile var time: Instant = Instant.parse("2026-09-30T00:00:00Z")) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Test
    fun `인증 후 전체 구독하고 조각난 뉴스 메시지를 한 번 저장한다`() {
        // given
        val clock = MutableClock()
        val props = mockk<ScoophubProperties>()
        every { props.alpaca } returns
            ScoophubProperties.Alpaca(apiKey = "test", apiSecret = "test", streamEnabled = true)
        val articles = mockk<NewsArticleService>(relaxed = true)
        val router = mockk<NotifyRouter>(relaxed = true)
        val connector = mockk<AlpacaSocketConnector>()
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.sendText(any(), any()) } returns CompletableFuture.completedFuture(socket)
        val listeners = CopyOnWriteArrayList<WebSocket.Listener>()
        every { connector.connect(any(), any()) } answers {
            val listener = secondArg<WebSocket.Listener>()
            listeners += listener
            listener.onOpen(socket)
            CompletableFuture.completedFuture(socket)
        }
        val stream =
            AlpacaNewsStream(
                props,
                JsonMapper.builder().build(),
                AlpacaNewsDecoder(),
                articles,
                router,
                clock,
                connector,
            )
        // when
        try {
            stream.start()
            verify(timeout = 3000) { socket.sendText(match { it.contains("auth") }, true) }
            val listener = listeners.single()
            listener.onText(socket, """[{"T":"success","msg":"authenticated"}]""", true)
            listener.onText(socket, """[{"T":"subscription","news":["*"]}]""", true)
            val message = """
                [{"T":"n","id":5000000001,"headline":"CPI update","symbols":[],
                "created_at":"2026-09-30T00:00:00Z","updated_at":"2026-09-30T00:00:01Z"}]
            """.trimIndent()
            listener.onText(socket, message.take(30), false)
            listener.onText(socket, message.drop(30), true)
            // then
            verify { socket.sendText(match { it.contains("subscribe") && it.contains("*") }, true) }
            verify(exactly = 1) { articles.receive(match { it.id == 5_000_000_001L && it.headline == "CPI update" }) }
            verify(exactly = 0) { router.dispatchConfirmed(any(), any(), any(), any()) }
        } finally {
            stream.stop()
        }
    }

    @Test
    fun `pong 누락은 재연결하고 긴 끊김과 복구는 각각 한 번 알린다`() {
        // given
        val clock = MutableClock()
        val props = mockk<ScoophubProperties>()
        every { props.alpaca } returns
            ScoophubProperties.Alpaca(apiKey = "test", apiSecret = "test", streamEnabled = true)
        val router = mockk<NotifyRouter>()
        every { router.dispatchConfirmed(any(), any(), any(), any()) } returns true
        val connector = mockk<AlpacaSocketConnector>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocket.Listener>()
        every { connector.connect(any(), any()) } answers {
            val listener = secondArg<WebSocket.Listener>()
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.sendText(any(), any()) } returns CompletableFuture.completedFuture(socket)
            every { socket.sendPing(any()) } returns CompletableFuture.completedFuture(socket)
            sockets += socket
            listeners += listener
            listener.onOpen(socket)
            CompletableFuture.completedFuture(socket)
        }
        val stream =
            AlpacaNewsStream(
                props,
                JsonMapper.builder().build(),
                AlpacaNewsDecoder(),
                mockk(relaxed = true),
                router,
                clock,
                connector,
            )
        // when
        try {
            stream.start()
            verify(timeout = 3000) { connector.connect(any(), any()) }
            while (listeners.isEmpty()) Thread.sleep(10)
            val socket = sockets.first()
            val listener = listeners.first()
            listener.onText(socket, """[{"T":"subscription","news":["*"]}]""", true)
            clock.time = clock.time.plusSeconds(31)
            verify(timeout = 3000) { socket.sendPing(any()) }
            // A matching pong keeps an idle connection alive.
            listener.onPong(socket, ByteBuffer.wrap("news-heartbeat".toByteArray()))
            clock.time = clock.time.plusSeconds(31)
            verify(timeout = 3000, exactly = 2) { socket.sendPing(any()) }
            clock.time = clock.time.plusSeconds(11)
            verify(timeout = 3000) { socket.abort() }
            clock.time = clock.time.plusSeconds(301)
            verify(timeout = 3000) {
                router.dispatchConfirmed("news", "alpaca", match { it.startsWith("news:socket:down:") }, any())
            }
            clock.time = clock.time.plusSeconds(2)
            verify(timeout = 3000, exactly = 2) { connector.connect(any(), any()) }
            val deadline = System.nanoTime() + 3_000_000_000L
            while (listeners.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertThat(listeners.size).isGreaterThanOrEqualTo(2)
            val restored = sockets[1]
            verify(timeout = 3000) { restored.sendText(any(), true) }
            listeners[1].onText(restored, """[{"T":"subscription","news":["*"]}]""", true)
            verify(timeout = 3000) {
                router.dispatchConfirmed("news", "alpaca", match { it.startsWith("news:socket:recovered:") }, any())
            }
            // then
            assertThat(sockets.size).isGreaterThanOrEqualTo(2)
            verify(exactly = 1) {
                router.dispatchConfirmed("news", "alpaca", match { it.startsWith("news:socket:down:") }, any())
            }
            verify(exactly = 1) {
                router.dispatchConfirmed("news", "alpaca", match { it.startsWith("news:socket:recovered:") }, any())
            }
        } finally {
            stream.stop()
        }
    }
}
