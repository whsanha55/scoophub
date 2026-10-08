package com.scoophub.news.service

import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.notify.NotifyCard
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.news.repository.NewsArticleQueryRepository
import com.scoophub.news.vo.AlpacaArticleRow
import com.scoophub.news.vo.ArticleAssessment
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

@Component
@DependsOnDatabaseInitialization
class NewsArticleWorker(
    private val repository: NewsArticleQueryRepository,
    private val filter: NewsRuleFilter,
    private val assessor: NewsBatchAssessor,
    private val router: NotifyRouter,
    private val props: ScoophubProperties,
    private val clock: Clock,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "news-worker").apply {
            isDaemon =
                true
        }
    }

    @PostConstruct
    fun start() {
        if (props.alpaca.workerEnabled) {
            executor.scheduleWithFixedDelay({
                try {
                    processPending()
                } catch (e: Exception) {
                    log.error(e) { "News worker failed" }
                }
            }, 5, 5, TimeUnit.SECONDS)
        }
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
    }

    @Synchronized
    fun processPending() {
        processBursts()
        while (!Thread.currentThread().isInterrupted) {
            val batch = repository.findPending(clock.instant())
            if (batch.isEmpty()) break
            processBatch(batch)
            processBursts()
        }
        processBursts()
    }

    private fun processBatch(batch: List<AlpacaArticleRow>) {
        val candidates = batch.filter { article ->
            val reason = if (isStale(article)) "stale" else filter.exclusionReason(article)
            if (reason !=
                null
            ) {
                repository.decide(
                    article.id,
                    if (reason ==
                        "stale"
                    ) {
                        "skipped"
                    } else {
                        "filtered"
                    },
                    reason,
                    clock.instant(),
                )
            }
            reason == null
        }
        val needsAssessment = candidates.filter { it.importance == null }
        val results = if (needsAssessment.isEmpty()) {
            emptyMap()
        } else {
            try {
                log.info { "News LLM batch: articles=${needsAssessment.size}" }
                assessor.assess(
                    needsAssessment,
                    props.alpaca.bigTechSymbols,
                    repository.findRecentPushedSummaries(clock.instant().minus(Duration.ofHours(2))),
                )
            } catch (e: Exception) {
                log.warn { "News LLM batch failed: articles=${needsAssessment.size}, type=${e.javaClass.simpleName}" }
                emptyMap()
            }
        }
        val pushes = mutableListOf<Pair<AlpacaArticleRow, String>>()
        for (article in candidates) {
            val bigTech = article.symbols.filter { it in props.alpaca.bigTechSymbols }
            val assessment = if (article.importance != null) {
                ArticleAssessment(article.importance, article.category.orEmpty(), article.summaryKo.orEmpty())
            } else {
                results[article.id]
            }
            if (assessment == null) {
                retry(article, "llm", bigTech.isNotEmpty())
                continue
            }
            if (article.importance == null) repository.saveAssessment(article.id, assessment)
            if (isStale(article)) {
                repository.decide(article.id, "skipped", "stale", clock.instant())
            } else if (assessment.duplicate) {
                repository.decide(article.id, "skipped", "duplicate", clock.instant())
            } else if (assessment.importance < 4) {
                repository.decide(article.id, "skipped", "importance score=${assessment.importance}", clock.instant())
            } else if (bigTech.isEmpty() && assessment.category != "거시") {
                repository.decide(article.id, "skipped", "out-of-scope score=${assessment.importance}", clock.instant())
            } else {
                val reason = if (bigTech.isNotEmpty()) {
                    "bigtech:${bigTech.joinToString(",")} score=${assessment.importance}"
                } else {
                    "macro score=${assessment.importance}"
                }
                pushes +=
                    article.copy(
                        importance = assessment.importance,
                        category = assessment.category,
                        summaryKo = assessment.summaryKo,
                    ) to
                    reason
            }
        }
        if (pushes.isNotEmpty()) {
            val success = router.dispatchBatch(
                "news",
                "alpaca",
                pushes.map { (article, _) ->
                    key(article) to card(article)
                },
            )
            for ((article, reason) in pushes) {
                if (success) {
                    repository.decide(
                        article.id,
                        "pushed",
                        reason,
                        clock.instant(),
                    )
                } else {
                    retry(article, "send", false)
                }
            }
        }
    }

    private fun retry(article: AlpacaArticleRow, stage: String, isBigTech: Boolean) {
        val attempts = article.attempts + 1
        if (attempts >= 3) {
            var reason = "$stage:exhausted"
            if (stage == "llm" && isBigTech && !isStale(article)) {
                val sent = router.dispatchConfirmed("news", "alpaca", key(article), card(article))
                reason += if (sent) ":headline-pushed" else ":headline-send-failed"
            }
            repository.retry(article.id, attempts, clock.instant(), reason)
            repository.decide(article.id, "failed", reason, clock.instant())
        } else {
            val delay = if (attempts == 1) 30L else 120L
            repository.retry(article.id, attempts, clock.instant().plusSeconds(delay), "$stage:retry")
        }
    }

    private fun processBursts() {
        val now = clock.instant()
        val since = now.minus(Duration.ofMinutes(30))
        for (symbol in repository.findBurstSymbols(since, now, props.alpaca.burstThreshold)) {
            if (symbol !in props.alpaca.bigTechSymbols ||
                symbol in props.alpaca.burstExcludedSymbols ||
                repository.hasRecentBurst(symbol, now.minus(Duration.ofHours(2)))
            ) {
                continue
            }
            val articles = repository.findBurstArticles(symbol, since, now)
            val count = if (articles.size == 10) "10건 이상" else "${articles.size}건"
            val body = articles.joinToString("\n") { "• ${boundedHtml(it.summaryKo ?: it.headline, 280)}" }
            router.dispatchConfirmed(
                "news",
                "alpaca",
                "news:burst:$symbol:${now.epochSecond / 7200}",
                NotifyMessage("🔥 ${boundedHtml(symbol, 60)} 30분 $count\n$body"),
            )
        }
    }

    private fun boundedHtml(text: String, limit: Int): String {
        val result = StringBuilder()
        for (codePoint in text.codePoints().toArray()) {
            val escaped = NotifyCard.escapeHtml(String(Character.toChars(codePoint)))
            if (result.length + escaped.length > limit) break
            result.append(escaped)
        }
        return result.toString()
    }

    private fun isStale(article: AlpacaArticleRow): Boolean =
        Duration.between(article.publishedAt, clock.instant()) > Duration.ofMinutes(15)
    private fun key(article: AlpacaArticleRow): String = "news:alpaca:${article.id}"
    private fun card(article: AlpacaArticleRow): NotifyMessage {
        val title = article.summaryKo?.takeIf { it.isNotBlank() }?.let { boundedHtml(it, 900) }
            ?: boundedHtml(article.headline, 600)
        val symbols = boundedHtml(article.symbols.joinToString(" · "), 250)
        val url = article.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.let { NotifyCard.escapeHtml(it).replace("\"", "&quot;") }
            ?.takeIf { it.length <= 800 }
        val link = if (url == null) "" else " <a href=\"${url}\">원문</a>"
        return NotifyMessage("<b>$title</b>\n$symbols$link")
    }
}
