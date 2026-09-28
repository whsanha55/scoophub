package com.scoophub.github

import com.scoophub.external.github.GithubTrendingClient
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.schedule.ScheduleResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `community/github_trending/crawler.py` — trending HTML → crawl_data(community, github) */
@Component
class GithubTrendingCrawler(
    private val client: GithubTrendingClient,
    private val store: CrawlDataStore,
    private val crawlDataRepository: CrawlDataRepository,
    private val scheduleResolver: ScheduleResolver,
    private val clock: Clock,
) : Crawler {
    override val name = "github_trending"

    /** crawl_logs.crawler_detail — 크롤 시점의 period */
    private var currentSince: String = "daily"

    override val detail: String
        get() = currentSince

    override fun fetch(): CrawlResult {
        // legacy from_config — crawl_config 파라미터 (since, language, max_repos)
        val params = scheduleResolver.resolveParams(name)
        val since = params["since"]?.takeIf { !it.isNull }?.asText() ?: "daily"
        val language = params["language"]?.takeIf { !it.isNull && !it.asText().isBlank() }?.asText()
        val maxRepos = params["max_repos"]?.asInt(25) ?: 25
        currentSince = since

        log.info { "github_trending fetch started — since=$since language=$language" }
        val repos = try {
            client.trending(language, since)
        } catch (e: Exception) {
            log.error(e) { "gtrending fetch failed: ${e.message}" }
            return CrawlResult(errors = listOf(e.message ?: e.toString()))
        }
        if (repos.isEmpty()) {
            return CrawlResult()
        }
        val top = repos.take(maxRepos)
        val fetchedAt = clock.instant()

        // crawl_data(category=community, purpose=github, key=url)
        val existingUrls = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn("community", "github", top.map { it.url })
            .map { it.key }
            .toSet()
        var itemsNew = 0
        val errors = mutableListOf<String>()
        for (repo in top) {
            try {
                store.upsert(
                    category = "community",
                    purpose = "github",
                    key = repo.url,
                    response = mapOf(
                        "fullname" to repo.fullname,
                        "author" to repo.author,
                        "name" to repo.name,
                        "url" to repo.url,
                        "description" to repo.description,
                        "language" to repo.language,
                        "stars" to repo.stars,
                        "forks" to repo.forks,
                        "current_period_stars" to repo.currentPeriodStars,
                        "period" to since,
                        "fetched_at" to fetchedAt.toString(),
                    ),
                    dateAt = fetchedAt,
                )
                if (repo.url !in existingUrls) {
                    itemsNew++
                }
            } catch (e: Exception) {
                errors.add("${repo.fullname}: ${e.message}")
                log.warn { "upsert failed for ${repo.fullname}: ${e.message}" }
            }
        }

        log.info { "github_trending fetch completed: fetched=${top.size} new=$itemsNew errors=${errors.size}" }
        return CrawlResult(itemsFetched = top.size, itemsNew = itemsNew, errors = errors)
    }
}
