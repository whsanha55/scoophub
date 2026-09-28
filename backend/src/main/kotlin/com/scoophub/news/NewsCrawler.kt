package com.scoophub.news

import com.scoophub.external.rss.RssClient
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.global.crawl.repository.CrawlLogRepository
import com.scoophub.global.schedule.ScheduleResolver
import com.scoophub.news.repository.FeedNewsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

private val HTML_TAG = Regex("<[^>]+>")

/** HTML 태그 제거 */
fun stripHtml(text: String): String = HTML_TAG.replace(text, "").trim()

/** legacy `feed/news/crawler.py` — RSS 소스 → feed_news (URL 정규화 dedup) */
@Component
class NewsCrawler(
    private val rssClient: RssClient,
    private val feedNewsRepository: FeedNewsRepository,
    private val crawlLogRepository: CrawlLogRepository,
    private val scheduleResolver: ScheduleResolver,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) : Crawler {
    override val name = "news"
    override val detail = "rss"

    data class Source(val id: Int, val name: String, val url: String)

    override fun fetch(): CrawlResult {
        // legacy from_config 와 스케줄러 kwargs 가 동일값 — crawl_config 사용
        val params = scheduleResolver.resolveParams(name)
        val maxLookbackHours = params["max_lookback_hours"]?.asInt(24) ?: 24

        val sources = activeSources()
        var totalFetched = 0
        var totalNew = 0
        var totalDeduped = 0
        val errors = mutableListOf<String>()
        val allNewIds = mutableListOf<Int>()

        // 이번 run 전체 소스가 공유하는 dedup 상태
        val seenUrls = mutableSetOf<String>()
        val cutoff = computeCutoff(maxLookbackHours)

        for (source in sources) {
            try {
                val (items, new, deduped, newIds) = fetchSource(source, seenUrls, cutoff)
                totalFetched += items
                totalNew += new
                totalDeduped += deduped
                allNewIds += newIds
            } catch (e: Exception) {
                val msg = "[${source.name}] ${e.message}"
                log.warn { msg }
                errors.add(msg)
            }
        }

        if (totalDeduped > 0) {
            log.info { "news URL dedup: skipped $totalDeduped duplicates" }
        }

        return CrawlResult(
            itemsFetched = totalFetched,
            itemsNew = totalNew,
            errors = errors,
            newArticleIds = allNewIds.map { it.toLong() },
        )
    }

    /** Cutoff = 직전 성공 크롤 이후. 없거나 오래됐으면 now - maxLookbackHours (백로그 폭발 방지) */
    private fun computeCutoff(maxLookbackHours: Int): Instant {
        val floor = clock.instant().minus(Duration.ofHours(maxLookbackHours.toLong()))
        val last = crawlLogRepository.findLastFinishedAt(name, detail)
        return if (last != null && last.isAfter(floor)) last else floor
    }

    private fun activeSources(): List<Source> = jdbcClient.sql(
        "SELECT id, name, url FROM crawl_sources WHERE crawler = 'news' AND active = true ORDER BY id",
    )
        .query { rs, _ -> Source(rs.getInt("id"), rs.getString("name"), rs.getString("url")) }
        .list()

    private fun fetchSource(source: Source, seenUrls: MutableSet<String>, cutoff: Instant): FetchStats {
        var fetched = 0
        var new = 0
        var deduped = 0
        val newIds = mutableListOf<Int>()

        for (entry in rssClient.fetch(source.url)) {
            val title = stripHtml(entry.title)
            val url = entry.link
            if (title.isEmpty() || url.isEmpty()) {
                continue
            }
            val description = stripHtml(entry.summary.orEmpty())
            val publishedAt = entry.published

            // cutoff 이후 발행 기사만 (발행일 없는 기사는 유지)
            if (publishedAt != null && publishedAt.isBefore(cutoff)) {
                continue
            }
            fetched++

            // Dedup: exact normalized URL only. 제목 dedup 은 LLM 담당.
            val nurl = NewsUrlNormalizer.normalize(url)
            if (nurl in seenUrls) {
                deduped++
                continue
            }

            try {
                val insertedId = feedNewsRepository.insertOnConflictIgnore(
                    source = source.name,
                    title = title,
                    summary = description.ifEmpty { null },
                    url = url,
                    normalizedUrl = nurl,
                    publishedAt = publishedAt,
                )
                if (insertedId != null) {
                    new++
                    newIds += insertedId
                    seenUrls += nurl
                } else {
                    deduped++
                }
            } catch (e: Exception) {
                log.warn { "DB insert failed for $url: ${e.message}" }
            }
        }
        return FetchStats(fetched, new, deduped, newIds)
    }

    private data class FetchStats(val fetched: Int, val new: Int, val deduped: Int, val newIds: List<Int>)
}
