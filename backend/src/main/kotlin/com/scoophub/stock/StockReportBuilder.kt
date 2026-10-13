package com.scoophub.stock

import com.scoophub.global.jackson.scalar
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.stock.dto.SigmaBandOut
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.service.StockSigmaService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val log = KotlinLogging.logger {}

/**
 * 일간 리포트 — 신규와 지속을 별도 메시지로 보낸다 (#267).
 * 신규: 마지막 거래일에 골든·데드크로스가 난 종목(#251)과 그날 처음 주간 ±1σ 밴드를 벗어난 종목(#260).
 * 지속: 전 거래일에도 같은 방향으로 밴드 밖이던 종목. 같은 종목이 한 주 내내 신규로 반복되지 않게 한다.
 */
@Component
class StockReportBuilder(
    private val analysisRepository: StockAnalysisResultRepository,
    private val watchlistRepository: StockWatchlistRepository,
    private val sigmaService: StockSigmaService,
    private val notifyRouter: NotifyRouter,
    private val clock: Clock,
) {
    /** 리포트 빌드 + 발신. 보낼 신규·지속 종목이 없으면 null 반환(발신 스킵) */
    fun run(tickers: List<String>? = null): String? {
        val watchlist = watchlistRepository.findByIsActiveOrderByAddedAt()
        val targets = tickers?.map { it.uppercase() } ?: watchlist.map { it.ticker }
        val groups = watchlist.associate { it.ticker to it.group }
        val rows = analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(targets, "1D")
            .filter { it.price > 0 }
            .sortedBy { it.ticker }
        val changed = rows.filter { isCrossedOnLastCandle(it) }
        val sigmaByTicker = sigmaService.findCurrentBands(rows)
        val breaks = rows.filter {
            sigmaByTicker[it.ticker]?.state.let { s -> s != null && s != SigmaStateEnum.NORMAL }
        }
        val previous = if (breaks.isEmpty()) emptyMap() else sigmaService.findPreviousStates(breaks)
        val (ongoing, fresh) = breaks.partition { previous[it.ticker] == sigmaByTicker.getValue(it.ticker).state }
        if (changed.isEmpty() && breaks.isEmpty()) {
            log.info { "ReportBuilder.run: no trend change or sigma break among ${targets.size} tickers — skip" }
            return null
        }

        val format = RowFormat(groups, sigmaByTicker)
        val candleDate = rows.firstNotNullOf { it.candleDate }
        val texts = mutableListOf<String>()
        if (changed.isNotEmpty() || fresh.isNotEmpty()) {
            texts += freshReport(candleDate, changed, fresh, sigmaByTicker, format)
            dispatch(texts.last(), payloadKey())
        }
        if (ongoing.isNotEmpty()) {
            texts += ongoingReport(candleDate, ongoing, format)
            dispatch(texts.last(), "${payloadKey()}:ongoing")
        }
        return texts.joinToString("\n\n")
    }

    private fun freshReport(
        candleDate: LocalDate,
        changed: List<StockAnalysisResultEntity>,
        fresh: List<StockAnalysisResultEntity>,
        sigmaByTicker: Map<String, SigmaBandOut>,
        format: RowFormat,
    ): String {
        val expiry = sigmaByTicker.values.groupingBy { it.expiryDate }.eachCount().maxByOrNull { it.value }?.key
        val lines = mutableListOf(
            "<b>📊 주식 리포트</b> · ${fmtDay(candleDate)} 마감" + (expiry?.let { " · 밴드 만기 ${fmtShort(it)}" } ?: ""),
        )
        marketLine()?.let { lines += it }

        val (golden, dead) = changed.partition { it.trend == TrendStateEnum.GOLDEN.name }
        section(lines, "🟢 골든크로스 (50일선 ↑ 200일선)", golden.map { format.cross(it) })
        section(lines, "🔴 데드크로스 (50일선 ↓ 200일선)", dead.map { format.cross(it) })
        val (upper, lower) = splitByDirection(fresh, sigmaByTicker)
        section(lines, "⬆️ +1σ 상단 돌파 · 신규", upper.map { "🆕 ${format.sigma(it)}" })
        section(lines, "⬇️ −1σ 하단 이탈 · 신규", lower.map { "🆕 ${format.sigma(it)}" })
        return "${lines.joinToString("\n")}\n\n$REPORT_LINK"
    }

    private fun ongoingReport(
        candleDate: LocalDate,
        ongoing: List<StockAnalysisResultEntity>,
        format: RowFormat,
    ): String {
        val (upper, lower) = splitByDirection(ongoing, format.sigmaByTicker)
        val lines = mutableListOf("<b>📌 밴드 밖 지속</b> · ${fmtDay(candleDate)} 마감", "")
        upper.forEach { lines += "⬆️ ${format.sigma(it)}" }
        lower.forEach { lines += "⬇️ ${format.sigma(it)}" }
        return lines.joinToString("\n")
    }

    /** 시장 지수 ETF 의 σ 위치와 일간 등락. 분석 행이 하나도 없으면 생략 */
    private fun marketLine(): String? {
        val rows = analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(MARKET_TICKERS, "1D")
            .filter { it.price > 0 }
            .associateBy { it.ticker }
        if (rows.isEmpty()) {
            return null
        }
        val sigmas = sigmaService.findCurrentBands(rows.values.toList())
        val items = MARKET_TICKERS.mapNotNull { ticker ->
            val row = rows[ticker] ?: return@mapNotNull null
            val position = sigmas[ticker]?.let { "${fmtSigma(it.position)} " } ?: ""
            "$ticker $position(${fmtRate(row.changeRate)})"
        }
        return "시장  ${items.joinToString(" · ")}"
    }

    /** 상단은 σ 큰 순, 하단은 σ 작은 순 */
    private fun splitByDirection(
        rows: List<StockAnalysisResultEntity>,
        sigmaByTicker: Map<String, SigmaBandOut>,
    ): Pair<List<StockAnalysisResultEntity>, List<StockAnalysisResultEntity>> {
        val (upper, lower) = rows.partition { sigmaByTicker.getValue(it.ticker).state == SigmaStateEnum.UPPER_BREAK }
        return upper.sortedByDescending { sigmaByTicker.getValue(it.ticker).position } to
            lower.sortedBy { sigmaByTicker.getValue(it.ticker).position }
    }

    private fun section(lines: MutableList<String>, title: String, rows: List<String>) {
        if (rows.isEmpty()) {
            return
        }
        lines += ""
        lines += title
        lines += rows
    }

    private fun dispatch(text: String, payloadKey: String) {
        val messages = split(text)
        messages.forEachIndexed { i, message ->
            val key = if (messages.size == 1) payloadKey else "$payloadKey:part-${i + 1}"
            notifyRouter.dispatch("stock", "daily-report", key, message)
        }
    }

    private fun payloadKey(): String = "stock:daily-report:${clock.instant().atZone(SEOUL).toLocalDate()}"

    /** trend_since 가 분석에 쓴 마지막 일봉과 같으면 그 거래일에 교차한 것 */
    private fun isCrossedOnLastCandle(row: StockAnalysisResultEntity): Boolean =
        row.trend != TrendStateEnum.UNKNOWN.name && row.trendSince != null && row.trendSince == row.candleDate

    /** 한 줄 형식 — `종목(상세 링크) · 테마  종가  등락` 뒤에 크로스는 이동평균, σ 는 위치 */
    private class RowFormat(val groups: Map<String, String>, val sigmaByTicker: Map<String, SigmaBandOut>) {
        fun cross(row: StockAnalysisResultEntity): String {
            val sma50 = row.technicalDetails.scalar("sma50")?.toDoubleOrNull()
            val sma200 = row.technicalDetails.scalar("sma200")?.toDoubleOrNull()
            return "${head(row)}  50일 ${fmtPrice(sma50)} · 200일 ${fmtPrice(sma200)}"
        }

        fun sigma(row: StockAnalysisResultEntity): String =
            "${head(row)}  ${fmtSigma(sigmaByTicker.getValue(row.ticker).position)}"

        private fun head(row: StockAnalysisResultEntity): String {
            val ticker = escapeHtml(row.ticker)
            val link = """<a href="$STOCK_URL/$ticker">$ticker</a>"""
            return "$link · ${groupLabel(groups[row.ticker])}  ${fmtPrice(row.price)}  ${fmtRate(row.changeRate)}"
        }
    }

    /** 4000자 초과 시 줄 단위 분할 */
    private fun split(text: String): List<NotifyMessage> {
        if (text.length <= TELEGRAM_MAX) {
            return listOf(NotifyMessage(text))
        }
        val messages = mutableListOf<NotifyMessage>()
        var buf = ""
        for (line in text.split("\n")) {
            if (buf.length + line.length + 1 > TELEGRAM_MAX && buf.isNotEmpty()) {
                messages += NotifyMessage(buf)
                buf = ""
            }
            buf = if (buf.isNotEmpty()) "$buf\n$line" else line
        }
        if (buf.isNotEmpty()) {
            messages += NotifyMessage(buf)
        }
        return messages
    }

    companion object {
        private const val TELEGRAM_MAX = 4000
        private const val STOCK_URL = "https://scoophub.gonamu.com/stock"
        private const val REPORT_LINK = """<a href="$STOCK_URL">🔗 Scoophub에서 전체 보기</a>"""
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val MARKET_TICKERS = listOf("SPY", "QQQ", "SOXX")
        private val SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd")

        /** 프론트 `domains/stock/lib/groups.ts` 와 같은 라벨 (V39 그룹 키) */
        private val GROUP_LABELS = mapOf(
            "etf_index" to "지수 ETF",
            "etf_sector" to "섹터·테마 ETF",
            "bigtech_software" to "빅테크·소프트웨어",
            "semiconductor" to "반도체",
            "semi_equipment" to "반도체 장비·설계",
            "ai_infra" to "AI 인프라",
            "internet_consumer" to "인터넷·소비",
            "healthcare" to "헬스케어",
            "industrial_energy" to "산업·에너지",
            "individual" to "기타",
        )

        fun groupLabel(key: String?): String = GROUP_LABELS[key] ?: key ?: "기타"

        fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun fmtPrice(v: Double?): String = if (v == null) "N/A" else "$%,.2f".format(v)

        fun fmtRate(v: Double): String = "%+.1f%%".format(v)

        fun fmtSigma(v: Double): String = "%+.2fσ".format(v)

        fun fmtShort(date: LocalDate): String = date.format(SHORT_DATE)

        /** 01/04(목) */
        fun fmtDay(date: LocalDate): String =
            "${fmtShort(date)}(${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
    }
}
