package com.scoophub.external.rss

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class RssClientTest {
    @Test
    fun `콜론 오프셋 pubDate 파싱`() {
        val xml = """
            <rss version="2.0"><channel><title>t</title>
            <item><title>a</title><link>https://x/1</link><pubDate>Tue, 29 Sep 2026 17:47:39 +09:00</pubDate></item>
            </channel></rss>
        """.trimIndent()

        val entry = RssClient(RestClient.builder()).parse(xml).single()

        assertThat(entry.published).isEqualTo(Instant.parse("2026-09-29T08:47:39Z"))
    }
}
