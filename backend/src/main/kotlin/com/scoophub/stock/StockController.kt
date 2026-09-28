package com.scoophub.stock

import com.scoophub.external.yahoo.YahooFinanceClient
import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ErrorDetail
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.global.jackson.scalar
import com.scoophub.stock.dto.ActionableLevelsOut
import com.scoophub.stock.dto.MarketStatusOut
import com.scoophub.stock.dto.SigmaOut
import com.scoophub.stock.dto.SigmaSnapshotOut
import com.scoophub.stock.dto.StockQuoteOut
import com.scoophub.stock.dto.StockReport
import com.scoophub.stock.dto.StockSummary
import com.scoophub.stock.dto.TechnicalOut
import com.scoophub.stock.dto.WatchlistItemIn
import com.scoophub.stock.dto.WatchlistItemOut
import com.scoophub.stock.dto.WatchlistUpdateIn
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.entity.StockWeeklyExpectedMoveEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockCandleRepository
import com.scoophub.stock.repository.StockSigmaRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.repository.StockWeeklyExpectedMoveRepository
import com.scoophub.stock.vo.SigmaModel
import com.scoophub.stock.vo.SigmaRange
import com.scoophub.stock.vo.WeeklyExpectedMove
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
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
import java.time.Duration
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
    private val provider: YahooFinanceClient,
    private val sigmaCrawler: SigmaCrawler,
    private val crawlRunner: CrawlRunner,
    private val watchlistRepository: StockWatchlistRepository,
    private val analysisRepository: StockAnalysisResultRepository,
    private val wemRepository: StockWeeklyExpectedMoveRepository,
    private val sigmaRepository: StockSigmaRepository,
    private val candleRepository: StockCandleRepository,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {

    // ── Stock Analysis ───────────────────────────────────────────────────────

    @Tag(name = "Stock Crawling")
    @Operation(summary = "분석 수동 실행")
    @SuperOnly
    @PostMapping("/crawling/stock/analyze")
    fun analyze(@RequestParam(name = "tickers") tickers: List<String>? = null): ApiResponse<AnalyzeResponse> {
        log.info { "analyze 엔드포인트 진입 — tickers=$tickers" }
        val targetTickers = tickers?.takeIf { it.isNotEmpty() }?.map { it.uppercase() }
            ?: watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }
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
        val rows = analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(tickerList, timeframe)
        return ApiResponse.ok(rows.map { buildReport(it) }, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Stock")
    @Operation(summary = "티커 상세 통합 조회")
    @GetMapping("/stock/detail/{ticker}")
    fun stockDetail(@PathVariable ticker: String): ApiResponse<StockReport?> {
        log.info { "stock_detail 엔드포인트 진입 — ticker=$ticker" }
        val upper = ticker.uppercase()
        val row = analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(listOf(upper), "1D")
            .firstOrNull()
            ?: return ApiResponse.ok(data = null, ResponseMeta(clock.instant()))
        val report = buildReport(row)
        attachQuote(report, upper)
        return ApiResponse.ok(report, ResponseMeta(clock.instant()))
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
        val rows = analysisRepository.findByTimeframeOrderByAnalyzedAtDesc(timeframe)
        if (!summarize) {
            val reports: List<Any> = rows.map { buildReport(it) }
            return ApiResponse.ok(reports, ResponseMeta(clock.instant()))
        }
        // 요약 시 sigma 폴백은 최근 1건만 (legacy limit=1)
        val summaries: List<Any> = rows.map { StockSummary.from(buildReport(it, sigmaFallbackLimit = 1), it) }
        return ApiResponse.ok(summaries, ResponseMeta(clock.instant()))
    }

    // ── Sigma (Options IV) ───────────────────────────────────────────────────

    @Tag(name = "Stock")
    @Operation(summary = "시그마 조회")
    @GetMapping("/stock/sigma")
    fun getSigma(@RequestParam ticker: String): ApiResponse<SigmaSnapshotOut?> {
        val result = sigmaRepository.findFirstByTickerOrderBySnapshotDateDescExpiryDateAsc(ticker.uppercase())
            ?: return ApiResponse.ok(data = null, ResponseMeta(clock.instant()))
        return ApiResponse.ok(SigmaSnapshotOut.from(result), ResponseMeta(clock.instant()))
    }

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
        val items = watchlistRepository.findAllByOrderByAddedAt().map { WatchlistItemOut.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Stock Watchlist")
    @Operation(summary = "관심종목 추가")
    @SuperOnly
    @PostMapping("/stock/watchlist")
    fun addWatchlist(@RequestBody item: WatchlistItemIn): ApiResponse<WatchlistItemOut> {
        log.info { "add_watchlist 엔드포인트 진입 — ticker=${item.ticker}" }
        return try {
            ApiResponse.ok(insertWatchlist(item), ResponseMeta(clock.instant()))
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
        val existing = findWatchlist(itemId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Watchlist item $itemId not found")

        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        addUpdateField(sets, params, "ticker", item.ticker)
        addUpdateField(sets, params, "exchange", item.exchange)
        addUpdateField(sets, params, "name", item.name)
        addUpdateField(sets, params, "memo", item.memo)
        addUpdateField(sets, params, "is_active", item.isActive)
        addUpdateField(sets, params, "\"group\"", item.group)
        if (sets.isEmpty()) {
            return ApiResponse.ok(existing, ResponseMeta(clock.instant()))
        }
        jdbcClient.sql("UPDATE stock_watchlist SET ${sets.joinToString(", ")} WHERE id = :id")
            .param("id", itemId)
            .params(params)
            .update()
        return ApiResponse.ok(findWatchlist(itemId) ?: existing, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Stock Watchlist")
    @Operation(summary = "관심종목 삭제")
    @SuperOnly
    @Transactional
    @DeleteMapping("/stock/watchlist/{item_id}")
    fun deleteWatchlist(@PathVariable("item_id") itemId: Int): ApiResponse<Map<String, Int>> {
        log.info { "delete_watchlist 엔드포인트 진입 — item_id=$itemId" }
        if (!watchlistRepository.existsById(itemId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Watchlist item $itemId not found")
        }
        // 관련 캔들/sigma/분석 결과까지 함께 삭제 (legacy remove 와 동일)
        watchlistRepository.deleteCandlesOf(itemId)
        watchlistRepository.deleteSigmaOf(itemId)
        watchlistRepository.deleteAnalysisOf(itemId)
        watchlistRepository.deleteByIdRow(itemId)
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
        val targetTickers = tickers?.takeIf { it.isNotEmpty() }?.map { it.uppercase() }
            ?: watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }
        if (targetTickers.isEmpty()) {
            return ApiResponse.ok(
                mapOf("saved" to 0, "tickers" to emptyList<String>()),
                ResponseMeta(clock.instant()),
            )
        }
        val snapshotAt = clock.instant()
        var saved = 0
        var errors = 0
        for (ticker in targetTickers) {
            try {
                val price = provider.quote(ticker)?.regularMarketPrice ?: 0.0
                if (price <= 0.0) {
                    errors++
                    continue
                }
                for (result in StockSigma.computeSigmaFromOptions(provider, ticker, price, snapshotAt, clock)) {
                    sigmaRepository.upsert(
                        ticker = result.ticker,
                        expiryDate = result.expiryDate ?: continue,
                        snapshotDate = result.snapshotDate,
                        snapshotAt = result.snapshotAt,
                        currentPrice = result.currentPrice,
                        atmStrike = result.atmStrike,
                        atmCall = result.atmCall,
                        atmPut = result.atmPut,
                        expectedMove = result.expectedMove,
                        expectedMovePct = result.expectedMovePct,
                        totalCallVolume = result.totalCallVolume,
                        totalPutVolume = result.totalPutVolume,
                        putCallVolumeRatio = result.putCallVolumeRatio,
                        atmCallVolume = result.atmCallVolume,
                        atmPutVolume = result.atmPutVolume,
                    )
                    saved++
                }
            } catch (e: Exception) {
                log.error(e) { "Sigma compute failed for $ticker" }
                errors++
            }
        }
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
        val items = watchlistRepository.findByIsActiveOrderByAddedAt()
        var totalSaved = 0
        for (item in items) {
            try {
                val candles = provider.chart(item.ticker, "1d")
                if (candles.isNotEmpty()) {
                    candleRepository.saveBatch(
                        tickers = candles.map { it.ticker }.toTypedArray(),
                        intervals = candles.map { it.interval }.toTypedArray(),
                        dates = candles.map { it.date }.toTypedArray(),
                        opens = candles.map { it.open }.toDoubleArray(),
                        highs = candles.map { it.high }.toDoubleArray(),
                        lows = candles.map { it.low }.toDoubleArray(),
                        closes = candles.map { it.close }.toDoubleArray(),
                        volumes = candles.map { it.volume }.toDoubleArray(),
                    )
                    totalSaved += candles.size
                }
            } catch (e: Exception) {
                log.warn { "Candle sync failed for ${item.ticker}: ${e.message}" }
            }
        }
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

    // ── Helpers (legacy router 헬퍼 포팅) ────────────────────────────────────

    /** `_analysis_row_to_report` + `_enrich_sigma_fallback` + `_enrich_report_levels` */
    private fun buildReport(row: StockAnalysisResultEntity, sigmaFallbackLimit: Int = 4): StockReport {
        val technical = TechnicalOut(
            signal = row.signal,
            totalScore = row.totalScore,
            confidence = row.confidence,
            marketRegime = row.marketRegime,
            technicalScores = row.technicalScores,
            technicalDetails = row.technicalDetails,
        )
        val report = StockReport(
            ticker = row.ticker,
            exchange = row.exchange,
            price = row.price,
            change = row.change,
            changeRate = row.changeRate,
            technical = technical,
            sigma = SigmaOut.fromPersistedSnapshot(row.technicalDetails),
            dataDate = row.analyzedAt,
            isStale = Duration.between(row.analyzedAt, clock.instant()).seconds > STALE_SECONDS,
        )
        if (report.sigma == null) {
            enrichSigmaFallback(report, row.ticker, sigmaFallbackLimit)
        }
        enrichReportLevels(report, row)
        return report
    }

    /** 저장 스냅샷이 없을 때 WEM 실시간 데이터로 report.sigma 채움 */
    private fun enrichSigmaFallback(report: StockReport, ticker: String, limit: Int) {
        val wemList = wemRepository.findByTickerOrderByWeekStartDesc(ticker, Limit.of(limit))
        if (wemList.isEmpty()) {
            return
        }
        report.sigma = SigmaOut.fromWem(wemList, report.price)
    }

    /** StockReport 에 actionable_levels + group 채우기 (#149) */
    private fun enrichReportLevels(report: StockReport, row: StockAnalysisResultEntity) {
        // 1. sigma range 확보 (저장된 snapshot 우선, 없으면 WEMRepo 실시간 산출)
        val wemSnapshot = row.technicalDetails.get("sigma_data")?.get("weekly_expected_move")
        val upper1 = wemSnapshot?.scalar("upper_1sigma")?.toDoubleOrNull()
        val lower1 = wemSnapshot?.scalar("lower_1sigma")?.toDoubleOrNull()
        val sigmaRange = if (upper1 != null && lower1 != null) {
            // compute_actionable_levels 는 upper/lower_1sigma 두 속성만 읽음
            SigmaRange(ticker = report.ticker, upper1sigma = upper1, lower1sigma = lower1)
        } else {
            val wem = wemRepository.findByTickerOrderByWeekStartDesc(report.ticker, Limit.of(1)).firstOrNull()
            if (wem != null) {
                try {
                    SigmaModel.computeSigmaRange(wem.toVo(), report.price)
                } catch (e: Exception) {
                    log.warn { "sigma range compute failed for ${report.ticker}: ${e.message}" }
                    null
                }
            } else {
                null
            }
        }

        StockReportBuilder.computeActionableLevels(report.price, sigmaRange, row.technicalDetails)?.let { levels ->
            report.actionableLevels = ActionableLevelsOut.from(levels)
        }

        // 2. group 매핑
        watchlistRepository.findByTickerAndIsActive(report.ticker)?.group?.takeIf { it.isNotEmpty() }?.let {
            report.group = it
        }
    }

    /** 실시간 quote. 실패 시 quote=None, 나머지 정상 (legacy /stock/detail) */
    private fun attachQuote(report: StockReport, ticker: String) {
        try {
            val q = provider.quote(ticker) ?: return
            if (q.regularMarketPrice != 0.0) {
                report.quote = StockQuoteOut(
                    price = q.regularMarketPrice,
                    change = q.regularMarketChange,
                    changeRate = q.regularMarketChangePercent,
                    volume = q.volume.takeIf { it != 0.0 },
                    high = q.high.takeIf { it != 0.0 },
                    low = q.low.takeIf { it != 0.0 },
                    open = q.open.takeIf { it != 0.0 },
                    timestamp = clock.instant(),
                )
            }
        } catch (e: Exception) {
            log.warn { "quote fetch failed for $ticker: ${e.message}" }
        }
    }

    private fun insertWatchlist(item: WatchlistItemIn): WatchlistItemOut = jdbcClient.sql(
        """
        INSERT INTO stock_watchlist (ticker, exchange, name, memo, is_active, "group")
        VALUES (:ticker, :exchange, :name, :memo, TRUE, :group)
        RETURNING id, ticker, exchange, name, memo, is_active, "group", added_at
        """,
    )
        .param("ticker", item.ticker.uppercase())
        .param("exchange", item.exchange.uppercase())
        .param("name", item.name)
        .param("memo", item.memo)
        .param("group", item.group.ifEmpty { "individual" })
        .query { rs, _ -> WatchlistItemOut.of(rs) }
        .single()

    private fun findWatchlist(itemId: Int): WatchlistItemOut? = jdbcClient.sql(
        """
        SELECT id, ticker, exchange, name, memo, is_active, "group", added_at
        FROM stock_watchlist WHERE id = :id
        """,
    )
        .param("id", itemId)
        .query { rs, _ -> WatchlistItemOut.of(rs) }
        .list()
        .firstOrNull()

    private fun addUpdateField(
        sets: MutableList<String>,
        params: MutableMap<String, Any>,
        column: String,
        value: Any?,
    ) {
        if (value != null) {
            val key = column.replace("\"", "")
            sets += "$column = :$key"
            params[key] = value
        }
    }

    private fun invalidTimeframe(timeframe: String) = ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Invalid timeframe '$timeframe'. Valid values: ['1D', '1M', '1W']",
    )

    private fun StockWeeklyExpectedMoveEntity.toVo() = WeeklyExpectedMove(
        id = id,
        ticker = ticker,
        weekStart = weekStart,
        weekEnd = weekEnd,
        expectedMoveHigh = expectedMoveHigh,
        expectedMoveLow = expectedMoveLow,
        expectedMovePct = expectedMovePct,
    )

    companion object {
        private val VALID_TIMEFRAMES = setOf("1D", "1W", "1M")
        private const val STALE_SECONDS = 86_400L // 24h — is_stale 기준
    }
}
