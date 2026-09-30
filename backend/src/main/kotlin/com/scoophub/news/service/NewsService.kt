package com.scoophub.news.service

import com.scoophub.news.repository.NewsQueryRepository
import com.scoophub.news.vo.NewsArticlePage
import com.scoophub.news.vo.NewsArticleRow
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class NewsService(private val queryRepository: NewsQueryRepository) {

    fun findArticles(
        minutes: Int?,
        from: Instant?,
        to: Instant?,
        category: String?,
        minImportance: Int?,
        limit: Int,
    ): NewsArticlePage = queryRepository.findArticles(minutes, from, to, category, minImportance, limit)

    fun findById(articleId: Int): NewsArticleRow? = queryRepository.findById(articleId)
}
