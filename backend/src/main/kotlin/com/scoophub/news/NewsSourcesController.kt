package com.scoophub.news

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.news.dto.NewsSourceItem
import com.scoophub.news.service.NewsSourceService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

private val log = KotlinLogging.logger {}

/** legacy `news/sources_router.py` — RSS 소스 CRUD */
@RestController
class NewsSourcesController(private val newsSourceService: NewsSourceService, private val clock: Clock) {
    data class SourceCreateRequest(val name: String, val url: String, val active: Boolean = true)

    data class SourceUpdateRequest(val name: String? = null, val url: String? = null, val active: Boolean? = null)

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 목록 조회")
    @GetMapping("/api/news/sources")
    fun listSources(
        @RequestParam(name = "active_only", defaultValue = "false") activeOnly: Boolean = false,
    ): ApiResponse<List<NewsSourceItem>> {
        val sources = newsSourceService.findAll(activeOnly).map { NewsSourceItem.from(it) }
        return ApiResponse.ok(
            sources,
            ResponseMeta(clock.instant(), total = sources.size, returned = sources.size),
        )
    }

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 추가")
    @SuperOnly
    @PostMapping("/api/news/sources")
    fun createSource(@RequestBody body: SourceCreateRequest): ApiResponse<NewsSourceItem> {
        val source = newsSourceService.create(body.name, body.url, body.active)
        return ApiResponse.ok(NewsSourceItem.from(source), ResponseMeta(clock.instant()))
    }

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 수정")
    @SuperOnly
    @PatchMapping("/api/news/sources/{source_id}")
    fun updateSource(
        @PathVariable("source_id") sourceId: Int,
        @RequestBody body: SourceUpdateRequest,
    ): ApiResponse<NewsSourceItem> {
        val source = newsSourceService.update(sourceId, body.name, body.url, body.active)
        return ApiResponse.ok(NewsSourceItem.from(source), ResponseMeta(clock.instant()))
    }

    @Tag(name = "News Sources")
    @Operation(summary = "뉴스 소스 삭제")
    @SuperOnly
    @DeleteMapping("/api/news/sources/{source_id}")
    fun deleteSource(@PathVariable("source_id") sourceId: Int): ApiResponse<Map<String, Boolean>> {
        newsSourceService.delete(sourceId)
        return ApiResponse.ok(mapOf("deleted" to true), ResponseMeta(clock.instant()))
    }
}
