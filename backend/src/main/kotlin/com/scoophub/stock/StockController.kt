package com.scoophub.stock

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ErrorDetail
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.stock.dto.MarketStatusOut
import com.scoophub.stock.dto.SigmaSnapshotOut
import com.scoophub.stock.dto.StockReport
import com.scoophub.stock.dto.WatchlistItemIn
import com.scoophub.stock.dto.WatchlistItemOut
import com.scoophub.stock.dto.WatchlistUpdateIn
import com.scoophub.stock.service.StockBacktestService
import com.scoophub.stock.service.StockCrawlService
import com.scoophub.stock.service.StockReportService
import com.scoophub.stock.service.StockWatchlistService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
    private val watchlistService: StockWatchlistService,
    private val reportService: StockReportService,
    private val crawlService: StockCrawlService,
    private val backtestService: StockBacktestService,
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
    fun stockReport(@RequestParam(defaultValue = "") tickers: String = ""): ApiResponse<List<StockReport>> {
        log.info { "stock_report 엔드포인트 진입 — tickers=$tickers" }
        if (tickers.isBlank()) {
            return ApiResponse.ok(emptyList(), ResponseMeta(clock.instant()))
        }
        val tickerList = tickers.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (tickerList.isEmpty()) {
            return ApiResponse.ok(emptyList(), ResponseMeta(clock.instant()))
        }
        return ApiResponse.ok(reportService.findReports(tickerList), ResponseMeta(clock.instant()))
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
    fun stockReportAll(@RequestParam(defaultValue = "false") summarize: Boolean = false): ApiResponse<List<Any>> {
        log.info { "stock_report_all 엔드포인트 진입 — summarize=$summarize" }
        val reports: List<Any> = if (summarize) {
            reportService.findAllSummaries()
        } else {
            reportService.findAll()
        }
        return ApiResponse.ok(reports, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Stock")
    @Operation(summary = "골든크로스 보유 규칙 백테스트 (관심종목 DB 캔들, 그냥 보유와 비교)")
    @GetMapping("/stock/backtest")
    fun backtest(@RequestParam(defaultValue = "0.001") cost: Double = 0.001): ApiResponse<BacktestResult?> {
        log.info { "backtest 엔드포인트 진입 — cost=$cost" }
        return ApiResponse.ok(backtestService.run(cost), ResponseMeta(clock.instant()))
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
        val outcome = crawlService.computeSigma(targetTickers)
        return ApiResponse.ok(
            mapOf("saved" to outcome.saved, "errors" to outcome.failures.size, "tickers" to targetTickers),
            ResponseMeta(clock.instant()),
        )
    }

    @Tag(name = "Stock Crawling")
    @Operation(summary = "캔들 동기화 수동 실행")
    @SuperOnly
    @PostMapping("/crawling/stock/sync")
    fun crawlingSync(): ApiResponse<Map<String, Int>> {
        log.info { "_do_sync_candles() 진입" }
        val outcome = crawlService.syncCandles()
        return ApiResponse.ok(mapOf("synced" to outcome.saved), ResponseMeta(clock.instant()))
    }

    // ── Daily Report Send (on-demand) ────────────────────────────────────────

    @Tag(name = "Stock")
    @Operation(summary = "추세 전환 리포트 발신")
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
                    message = "마지막 거래일에 골든·데드크로스가 난 종목이 없어 발신하지 않았습니다.",
                ),
                meta = ResponseMeta(clock.instant()),
            )
        return ApiResponse.ok(
            mapOf("sent" to true, "length" to reportText.length),
            ResponseMeta(clock.instant()),
        )
    }
}
