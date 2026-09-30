package com.scoophub.stock

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ErrorDetail
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.stock.dto.MarketStatusOut
import com.scoophub.stock.dto.SigmaSnapshotOut
import com.scoophub.stock.dto.StockReport
import com.scoophub.stock.dto.WatchlistItemIn
import com.scoophub.stock.dto.WatchlistItemOut
import com.scoophub.stock.dto.WatchlistUpdateIn
import com.scoophub.stock.service.StockCrawlService
import com.scoophub.stock.service.StockReportService
import com.scoophub.stock.service.StockWatchlistService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.ZoneOffset

private val log = KotlinLogging.logger {}

/**
 * legacy `stock/router.py` — 종목 분석/리포트/시그마/관심종목/수동 크롤 트리거.
 * GET(report, sigma, watchlist 조회, market-status) 공개, mutation 은 @SuperOnly.
 */
@RestController
@RequestMapping("/api")
class StockController(
    private val analysisService: StockAnalysisService,
    private val reportBuilder: StockReportBuilder,
    private val sigmaCrawler: SigmaCrawler,
    private val crawlRunner: CrawlRunner,
    private val watchlistService: StockWatchlistService,
    private val reportService: StockReportService,
    private val crawlService: StockCrawlService,
    private val clock: Clock,
) {

    // ── Stock Analysis ───────────────────────────────────────────────────────

    @Tag(name = "Stock Crawling")
    @Operation(summary = "분석 수동 실행")
    @SuperOnly
    @PostMapping("/crawling/stock/analyze")
    fun analyze(@RequestParam(name = "tickers") tickers: List<String>? = null): ApiResponse<AnalyzeResponse> {
        log.info { "analyze 엔드포인트 진입 — tickers=$tickers" }
        val targetTickers = watchlistService.resolveTickers(tickers)
        if (targetTickers.isEmpty()) {
            return ApiResponse.ok(
                AnalyzeResponse(total = 0, ok = 0, errors = 0, results = emptyList()),
                ResponseMeta(clock.instant()),
            )
        }
        return ApiResponse.ok(analysisService.runAnalysisForTickers(targetTickers), ResponseMeta(clock.instant()))
    }

    // ── Stock Report ─────────────────────────────────────────────────────────

    @Tag(name = "Stock")
    @Operation(summary = "종목 리포트")
    @GetMapping("/stock/report")
    fun stockReport(
        @RequestParam(defaultValue = "") tickers: String = "",
        @RequestParam(defaultValue = "1D") timeframe: String = "1D",
    ): ApiResponse<List<StockReport>> {
        log.info { "stock_report 엔드포인트 진입 — tickers=$tickers, timeframe=$timeframe" }
        if (timeframe !in VALID_TIMEFRAMES) {
            throw invalidTimeframe(timeframe)
        }
        if (tickers.isBlank()) {
            return ApiResponse.ok(emptyList(), ResponseMeta(clock.instant()))
        }
        val tickerList = tickers.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (tickerList.isEmpty()) {
            return ApiResponse.ok(emptyList(), ResponseMeta(clock.instant()))
        }
        return ApiResponse.ok(reportService.findReports(tickerList, timeframe), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Stock")
    @Operation(summary = "티커 상세 통합 조회")
    @GetMapping("/stock/detail/{ticker}")
    fun stockDetail(@PathVariable ticker: String): ApiResponse<StockReport?> {
        log.info { "stock_detail 엔드포인트 진입 — ticker=$ticker" }
        return ApiResponse.ok(reportService.findDetail(ticker.uppercase()), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Stock")
    @Operation(summary = "전체 리포트")
    @GetMapping("/stock/report/all")
    fun stockReportAll(
        @RequestParam(defaultValue = "false") summarize: Boolean = false,
        @RequestParam(defaultValue = "1D") timeframe: String = "1D",
    ): ApiResponse<List<Any>> {
        log.info { "stock_report_all 엔드포인트 진입 — summarize=$summarize, timeframe=$timeframe" }
        if (timeframe !in VALID_TIMEFRAMES) {
            throw invalidTimeframe(timeframe)
        }
        val reports: List<Any> = if (summarize) {
            reportService.findAllSummaries(timeframe)
        } else {
            reportService.findAll(timeframe)
        }
        return ApiResponse.ok(reports, ResponseMeta(clock.instant()))
    }

    // ── Sigma (Options IV) ───────────────────────────────────────────────────

    @Tag(name = "Stock")
    @Operation(summary = "시그마 조회")
    @GetMapping("/stock/sigma")
    fun getSigma(@RequestParam ticker: String): ApiResponse<SigmaSnapshotOut?> =
        ApiResponse.ok(reportService.findLatestSigma(ticker.uppercase()), ResponseMeta(clock.instant()))

    // ── Market Status ────────────────────────────────────────────────────────

    @Tag(name = "Stock")
    @Operation(summary = "시장 상태")
    @GetMapping("/stock/market-status")
    fun marketStatus(): ApiResponse<MarketStatusOut> {
        log.info { "market_status 엔드포인트 진입" }
        val nowUtc = clock.instant().atZone(ZoneOffset.UTC)
        val isWeekday = nowUtc.dayOfWeek.value < 6
        // US market hours: 14:30–21:00 UTC (9:30 AM–4:00 PM ET)
        val isMarketHours = (nowUtc.hour == 14 && nowUtc.minute >= 30) || nowUtc.hour in 15..20
        return ApiResponse.ok(
            MarketStatusOut(isOpen = isWeekday && isMarketHours, isWeekday = isWeekday, currentUtc = clock.instant()),
            ResponseMeta(clock.instant()),
        )
    }

    // ── Watchlist ────────────────────────────────────────────────────────────

    @Tag(name = "Stock Watchlist")
    @Operation(summary = "관심종목 전체 조회")
    @GetMapping("/stock/watchlist")
    fun getWatchlist(): ApiResponse<List<WatchlistItemOut>> {
        log.info { "get_watchlist 엔드포인트 진입" }
        val items = watchlistService.findAll().map { WatchlistItemOut.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Stock Watchlist")
    @Operation(summary = "관심종목 추가")
    @SuperOnly
    @PostMapping("/stock/watchlist")
    fun addWatchlist(@RequestBody item: WatchlistItemIn): ApiResponse<WatchlistItemOut> {
        log.info { "add_watchlist 엔드포인트 진입 — ticker=${item.ticker}" }
        return try {
            ApiResponse.ok(WatchlistItemOut.from(watchlistService.add(item)), ResponseMeta(clock.instant()))
        } catch (e: DataIntegrityViolationException) {
            log.warn { "watchlist add duplicate — ticker=${item.ticker}: ${e.message}" }
            ApiResponse(
                success = false,
                error = ErrorDetail(code = "duplicate", message = "${item.ticker} already in watchlist"),
                meta = ResponseMeta(clock.instant()),
            )
        }
    }

    @Tag(name = "Stock Watchlist")
    @Operation(summary = "관심종목 수정")
    @SuperOnly
    @PutMapping("/stock/watchlist/{item_id}")
    fun updateWatchlist(
        @PathVariable("item_id") itemId: Int,
        @RequestBody item: WatchlistUpdateIn,
    ): ApiResponse<WatchlistItemOut> {
        log.info { "update_watchlist 엔드포인트 진입 — item_id=$itemId" }
        return ApiResponse.ok(
            WatchlistItemOut.from(watchlistService.update(itemId, item)),
            ResponseMeta(clock.instant()),
        )
    }

    @Tag(name = "Stock Watchlist")
    @Operation(summary = "관심종목 삭제")
    @SuperOnly
    @DeleteMapping("/stock/watchlist/{item_id}")
    fun deleteWatchlist(@PathVariable("item_id") itemId: Int): ApiResponse<Map<String, Int>> {
        log.info { "delete_watchlist 엔드포인트 진입 — item_id=$itemId" }
        watchlistService.delete(itemId)
        return ApiResponse.ok(mapOf("deleted" to itemId), ResponseMeta(clock.instant()))
    }

    // ── Manual Crawl Triggers ────────────────────────────────────────────────

    @Tag(name = "Stock Crawling")
    @Operation(summary = "Sigma(1σ) 주간 예상 변동폭 크롤 (월 03:00 자동 / 수동 트리거)")
    @SuperOnly
    @PostMapping("/crawling/stock/sigma")
    fun crawlingSigma(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(sigmaCrawler, "Sigma")

    @Tag(name = "Stock Crawling")
    @Operation(summary = "Sigma 즉시 계산 (ATM straddle 기반)")
    @SuperOnly
    @PostMapping("/crawling/stock/sigma/compute")
    fun computeSigma(@RequestParam(name = "tickers") tickers: List<String>? = null): ApiResponse<Map<String, Any>> {
        val targetTickers = watchlistService.resolveTickers(tickers)
        if (targetTickers.isEmpty()) {
            return ApiResponse.ok(
                mapOf("saved" to 0, "tickers" to emptyList<String>()),
                ResponseMeta(clock.instant()),
            )
        }
        val (saved, errors) = crawlService.computeSigma(targetTickers)
        return ApiResponse.ok(
            mapOf("saved" to saved, "errors" to errors, "tickers" to targetTickers),
            ResponseMeta(clock.instant()),
        )
    }

    @Tag(name = "Stock Crawling")
    @Operation(summary = "캔들 동기화 수동 실행")
    @SuperOnly
    @PostMapping("/crawling/stock/sync")
    fun crawlingSync(): ApiResponse<Map<String, Int>> {
        log.info { "_do_sync_candles() 진입" }
        val totalSaved = crawlService.syncCandles()
        return ApiResponse.ok(mapOf("synced" to totalSaved), ResponseMeta(clock.instant()))
    }

    // ── Daily Report Send (on-demand) ────────────────────────────────────────

    @Tag(name = "Stock")
    @Operation(summary = "일간 분석 리포트 발신")
    @SuperOnly
    @PostMapping("/stock/report/send")
    fun sendStockReport(@RequestParam(name = "tickers") tickers: List<String>? = null): ApiResponse<Map<String, Any>> {
        log.info { "send_stock_report 엔드포인트 진입 — tickers=$tickers" }
        val target = tickers?.takeIf { it.isNotEmpty() }?.map { it.uppercase() }
        val reportText = reportBuilder.run(target)
            ?: return ApiResponse(
                success = false,
                error = ErrorDetail(
                    code = "no_data",
                    message = "발신할 분석 데이터가 없습니다. 먼저 /crawling/stock/analyze 실행 필요.",
                ),
                meta = ResponseMeta(clock.instant()),
            )
        return ApiResponse.ok(
            mapOf("sent" to true, "length" to reportText.length),
            ResponseMeta(clock.instant()),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun invalidTimeframe(timeframe: String) = ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Invalid timeframe '$timeframe'. Valid values: ['1D', '1M', '1W']",
    )

    companion object {
        private val VALID_TIMEFRAMES = setOf("1D", "1W", "1M")
    }
}
