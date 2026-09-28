package com.scoophub.youtube

import com.scoophub.external.youtube.YoutubeClient
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.jackson.elements
import com.scoophub.global.jackson.scalar
import com.scoophub.global.schedule.ScheduleResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

/** legacy `feed/youtube_trending/crawler.py` — mostPopular 차트 → crawl_data(feed, youtube) */
@Component
class YoutubeTrendingCrawler(
    private val client: YoutubeClient,
    private val store: CrawlDataStore,
    private val crawlDataRepository: CrawlDataRepository,
    private val scheduleResolver: ScheduleResolver,
    private val clock: Clock,
) : Crawler {
    override val name = "youtube_trending"
    override val detail = "most_popular"

    override fun fetch(): CrawlResult {
        // legacy from_config(yaml) 과 동일값 — region_codes/max_results_per_region 는 crawl_config 사용
        val params = scheduleResolver.resolveParams(name)
        val regions = params["region_codes"].elements().map { it.asText() }.ifEmpty { listOf("KR", "US") }
        val maxPerRegion = params["max_results_per_region"]?.asInt(50) ?: 50

        log.info { "youtube_trending fetch started — regions=$regions" }
        val errors = mutableListOf<String>()
        val fetchedAt = clock.instant()

        data class YtItem(
            val videoId: String,
            val title: String,
            val channelTitle: String,
            val channelId: String,
            val description: String?,
            val categoryId: String?,
            val publishedAtRaw: String?,
            val viewCount: Long,
            val likeCount: Long,
            val commentCount: Long,
            val duration: String?,
            val thumbnailUrl: String?,
            val regionCode: String,
        )

        val allItems = mutableListOf<YtItem>()
        for (region in regions) {
            try {
                val response = client.mostPopular(region, maxPerRegion)
                for (item in response["items"].elements()) {
                    val snippet = item["snippet"]
                    val statistics = item["statistics"]
                    val contentDetails = item["contentDetails"]
                    val thumbnails = snippet["thumbnails"]
                    allItems += YtItem(
                        videoId = item["id"].asText(),
                        title = snippet.scalar("title").orEmpty(),
                        channelTitle = snippet.scalar("channelTitle").orEmpty(),
                        channelId = snippet.scalar("channelId").orEmpty(),
                        description = snippet.scalar("description"),
                        categoryId = snippet.scalar("categoryId"),
                        publishedAtRaw = snippet.scalar("publishedAt"),
                        viewCount = statistics.scalar("viewCount")?.toLongOrNull() ?: 0L,
                        likeCount = statistics.scalar("likeCount")?.toLongOrNull() ?: 0L,
                        commentCount = statistics.scalar("commentCount")?.toLongOrNull() ?: 0L,
                        duration = contentDetails.scalar("duration"),
                        thumbnailUrl = (thumbnails["high"] ?: thumbnails["medium"])?.get("url")?.asText(),
                        regionCode = region,
                    )
                }
            } catch (e: Exception) {
                errors.add("$region: ${e.message}")
                log.warn { "failed to fetch region $region: ${e.message}" }
            }
        }
        if (allItems.isEmpty()) {
            return CrawlResult(errors = errors)
        }

        // crawl_data(category=feed, purpose=youtube, key={region_code}:{video_id})
        val existingKeys = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn("feed", "youtube", allItems.map { "${it.regionCode}:${it.videoId}" })
            .map { it.key }
            .toSet()
        var itemsNew = 0
        for (item in allItems) {
            try {
                val publishedAt =
                    item.publishedAtRaw?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: fetchedAt
                val key = "${item.regionCode}:${item.videoId}"
                store.upsert(
                    category = "feed",
                    purpose = "youtube",
                    key = key,
                    response = mapOf(
                        "video_id" to item.videoId,
                        "title" to item.title,
                        "channel_title" to item.channelTitle,
                        "channel_id" to item.channelId,
                        "description" to item.description,
                        "category_id" to item.categoryId,
                        "published_at" to publishedAt.toString(),
                        "view_count" to item.viewCount,
                        "like_count" to item.likeCount,
                        "comment_count" to item.commentCount,
                        "duration" to item.duration,
                        "thumbnail_url" to item.thumbnailUrl,
                        "region_code" to item.regionCode,
                        "fetched_at" to fetchedAt.toString(),
                    ),
                    dateAt = publishedAt,
                )
                if (key !in existingKeys) {
                    itemsNew++
                }
            } catch (e: Exception) {
                errors.add("${item.videoId}: ${e.message}")
                log.warn { "upsert failed for ${item.videoId}: ${e.message}" }
            }
        }

        log.info { "youtube_trending fetch completed: fetched=${allItems.size} new=$itemsNew errors=${errors.size}" }
        return CrawlResult(itemsFetched = allItems.size, itemsNew = itemsNew, errors = errors)
    }
}
