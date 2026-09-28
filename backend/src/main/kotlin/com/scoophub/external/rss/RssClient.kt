package com.scoophub.external.rss

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.StringReader
import java.time.Instant

data class RssEntry(
    val title: String,
    val link: String,
    val summary: String?,
    val author: String?,
    val category: String?,
    val published: Instant?,
)

/** RSS/Atom 파싱 (legacy `feedparser` 대체 — Rome) */
@Component
class RssClient(restClientBuilder: RestClient.Builder) {
    private val restClient = restClientBuilder.build()

    fun fetch(url: String): List<RssEntry> {
        val xml = restClient.get().uri(url).retrieve().body(String::class.java) ?: return emptyList()
        return parse(xml)
    }

    /** XML 문자열 → 엔트리. 테스트를 위해 분리 */
    fun parse(xml: String): List<RssEntry> {
        val feed: SyndFeed = SyndFeedInput().build(StringReader(xml))
        return feed.entries.map { entry ->
            RssEntry(
                title = entry.title.orEmpty(),
                link = entry.link.orEmpty(),
                summary = entry.description?.value,
                author = entry.author?.ifEmpty { null },
                category = entry.categories.firstOrNull()?.name,
                published = entry.publishedDate?.toInstant()
                    ?: entry.updatedDate?.toInstant(),
            )
        }
    }
}
