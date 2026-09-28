package com.scoophub.news

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.system.dto.NewsSourceItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `news/sources_router.py` — RSS 소스 CRUD */
@RestController
class NewsSourcesController(
    private val jdbcClient: JdbcClient,
    private val jsonMapper: tools.jackson.databind.json.JsonMapper,
    private val clock: Clock,
) {
    data class SourceCreateRequest(val name: String, val url: String, val active: Boolean = true)

    data class SourceUpdateRequest(val name: String? = null, val url: String? = null, val active: Boolean? = null)

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 목록 조회")
    @GetMapping("/api/news/sources")
    fun listSources(
        @RequestParam(name = "active_only", defaultValue = "false") activeOnly: Boolean = false,
    ): ApiResponse<List<NewsSourceItem>> {
        val where = if (activeOnly) "AND active = true" else ""
        val sources = jdbcClient.sql(
            "SELECT id, crawler, name, url, active, config, created_at, updated_at " +
                "FROM crawl_sources WHERE crawler = 'news' $where ORDER BY id",
        )
            .query { rs, _ -> NewsSourceItem.of(rs, jsonMapper) }
            .list()
        return ApiResponse.ok(
            sources,
            ResponseMeta(clock.instant(), total = sources.size, returned = sources.size),
        )
    }

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 추가")
    @SuperOnly
    @PostMapping("/api/news/sources")
    fun createSource(@RequestBody body: SourceCreateRequest): ApiResponse<NewsSourceItem> = try {
        val id = jdbcClient.sql(
            "INSERT INTO crawl_sources (crawler, name, url, active, config) " +
                "VALUES ('news', :name, :url, :active, '{}'::jsonb) RETURNING id",
        )
            .param("name", body.name)
            .param("url", body.url)
            .param("active", body.active)
            .query { rs, _ -> rs.getInt(1) }
            .single()
        ApiResponse.ok(findSource(id)!!, ResponseMeta(clock.instant()))
    } catch (e: DuplicateKeyException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, "Source URL already exists for news crawler")
    }

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 수정")
    @SuperOnly
    @PatchMapping("/api/news/sources/{source_id}")
    fun updateSource(
        @PathVariable("source_id") sourceId: Int,
        @RequestBody body: SourceUpdateRequest,
    ): ApiResponse<NewsSourceItem> {
        val existing = findSource(sourceId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found")

        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        body.name?.let {
            sets += "name = :name"
            params["name"] = it
        }
        body.url?.let {
            sets += "url = :url"
            params["url"] = it
        }
        body.active?.let {
            sets += "active = :active"
            params["active"] = it
        }
        if (sets.isEmpty()) {
            return ApiResponse.ok(existing, ResponseMeta(clock.instant()))
        }
        jdbcClient.sql("UPDATE crawl_sources SET ${sets.joinToString(", ")}, updated_at = now() WHERE id = :id")
            .param("id", sourceId)
            .params(params)
            .update()
        return ApiResponse.ok(findSource(sourceId)!!, ResponseMeta(clock.instant()))
    }

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 삭제")
    @SuperOnly
    @DeleteMapping("/api/news/sources/{source_id}")
    fun deleteSource(@PathVariable("source_id") sourceId: Int): ApiResponse<Map<String, Boolean>> {
        val deleted = jdbcClient.sql("DELETE FROM crawl_sources WHERE id = :id AND crawler = 'news'")
            .param("id", sourceId)
            .update()
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found")
        }
        return ApiResponse.ok(mapOf("deleted" to true), ResponseMeta(clock.instant()))
    }

    private fun findSource(id: Int): NewsSourceItem? = jdbcClient.sql(
        "SELECT id, crawler, name, url, active, config, created_at, updated_at FROM crawl_sources WHERE id = :id",
    )
        .param("id", id)
        .query { rs, _ -> NewsSourceItem.of(rs, jsonMapper) }
        .optional()
        .orElse(null)
}
