package com.scoophub.system

import com.scoophub.external.llm.LlmClient
import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ErrorDetail
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.crawl.repository.CrawlLogRepository
import com.scoophub.system.dto.CrawlLogItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Limit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

private val log = KotlinLogging.logger {}

@RestController
class SystemController(
    private val llmClient: LlmClient,
    private val props: ScoophubProperties,
    private val crawlLogRepository: CrawlLogRepository,
    private val clock: Clock,
) {
    @Tag(name = "System")
    @Operation(summary = "헬스 체크")
    @GetMapping("/api/health")
    fun health(): ApiResponse<Map<String, String>> {
        log.info { "health check requested" }
        return ApiResponse.ok(mapOf("status" to "ok"), ResponseMeta(clock.instant()))
    }

    data class LlmTestRequest(val message: String, val system: String = "You are a helpful assistant.")

    data class LlmTestData(val model: String, val content: String)

    @Tag(name = "System")
    @Operation(summary = "LLM 호출 테스트")
    @SuperOnly
    @PostMapping("/api/llm/test")
    fun llmTest(@RequestBody body: LlmTestRequest): ApiResponse<LlmTestData> {
        log.info { "llm test requested: message=${body.message}" }
        return try {
            val content = llmClient.chat(body.system, body.message)
            log.info { "llm test success: model=${props.llm.model}" }
            ApiResponse.ok(LlmTestData(props.llm.model, content), ResponseMeta(clock.instant()))
        } catch (e: Exception) {
            log.error { "llm test failed: ${e.message}" }
            ApiResponse(
                success = false,
                error = ErrorDetail(code = "llm_failed", message = e.message ?: e.javaClass.simpleName),
                meta = ResponseMeta(clock.instant()),
            )
        }
    }

    @Tag(name = "System")
    @Operation(summary = "크롤 실행 로그 조회")
    @GetMapping("/api/crawl-logs")
    fun crawlLogs(
        @RequestParam(name = "crawler") crawler: String? = null,
        @RequestParam(name = "crawler_detail") crawlerDetail: String? = null,
        @RequestParam(defaultValue = "20") limit: Int = 20,
    ): ApiResponse<List<CrawlLogItem>> {
        log.info { "crawl logs requested: crawler=$crawler detail=$crawlerDetail limit=$limit" }
        val rows = when {
            crawler != null && crawlerDetail != null ->
                crawlLogRepository.findByCrawlerAndCrawlerDetailOrderByStartedAtDesc(
                    crawler,
                    crawlerDetail,
                    Limit.of(limit),
                )

            crawler != null ->
                crawlLogRepository.findByCrawlerOrderByStartedAtDesc(crawler, Limit.of(limit))

            crawlerDetail != null ->
                crawlLogRepository.findByCrawlerDetailOrderByStartedAtDesc(crawlerDetail, Limit.of(limit))

            else -> crawlLogRepository.findAllByOrderByStartedAtDesc(Limit.of(limit))
        }
        val logs = rows.map { CrawlLogItem.from(it) }
        return ApiResponse.ok(logs, ResponseMeta(clock.instant(), total = logs.size, returned = logs.size))
    }
}
