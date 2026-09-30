package com.scoophub.news.service

import com.scoophub.news.repository.NewsSourceQueryRepository
import com.scoophub.news.vo.NewsSourceRow
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class NewsSourceService(private val repository: NewsSourceQueryRepository) {

    fun findAll(activeOnly: Boolean): List<NewsSourceRow> = repository.findAll(activeOnly)

    fun create(name: String, url: String, active: Boolean): NewsSourceRow {
        val id = try {
            repository.insert(name, url, active)
        } catch (e: DuplicateKeyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Source URL already exists for news crawler")
        }
        return requireNotNull(repository.findById(id))
    }

    fun update(id: Int, name: String?, url: String?, active: Boolean?): NewsSourceRow {
        val existing = repository.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found")
        if (!repository.update(id, name, url, active)) {
            return existing
        }
        return requireNotNull(repository.findById(id))
    }

    fun delete(id: Int) {
        if (repository.delete(id) == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found")
        }
    }
}
