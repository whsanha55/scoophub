package com.scoophub.news.service

import com.scoophub.news.repository.NewsArticleQueryRepository
import com.scoophub.news.vo.AlpacaArticlePage
import com.scoophub.news.vo.AlpacaArticleRow
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.util.Locale

@Service
class NewsArticleService(private val repository: NewsArticleQueryRepository, private val clock: Clock) {
    @Transactional
    fun receive(article: AlpacaArticleRow): Unit = repository.upsert(article)

    fun findArticles(
        minutes: Int?,
        from: Instant?,
        to: Instant?,
        category: String?,
        minImportance: Int?,
        symbol: String?,
        limit: Int,
        page: Int,
    ): AlpacaArticlePage {
        if (limit !in 1..100 || page < 1 || (minutes != null && minutes < 1) ||
            (minImportance != null && minImportance !in 1..5) || (from != null && to != null && from > to)
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid news filters")
        }
        val since = if (minutes !=
            null
        ) {
            clock.instant().minusSeconds(minutes.toLong() * 60)
        } else {
            from ?: clock.instant().minusSeconds(1800)
        }
        return repository.findArticles(
            since,
            to,
            category,
            minImportance,
            symbol?.trim()?.uppercase(Locale.ROOT),
            limit,
            page,
        )
    }

    fun findById(id: Long): AlpacaArticleRow? = repository.findById(id)
}
