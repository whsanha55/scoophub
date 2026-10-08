package com.scoophub.stock

import com.scoophub.global.jackson.scalar
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/** 일간 추세 전환 리포트 — 마지막 거래일에 골든·데드크로스가 난 종목만 발신 (#251) */
@Component
class StockReportBuilder(
    private val analysisRepository: StockAnalysisResultRepository,
    private val watchlistRepository: StockWatchlistRepository,
    private val notifyRouter: NotifyRouter,
    private val clock: Clock,
) {
    /** 리포트 빌드 + 발신. 전환 종목이 없으면 null 반환(발신 스킵) */
    fun run(tickers: List<String>? = null): String? {
        val targets = tickers?.map { it.uppercase() }
            ?: watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }
        val changed = analysisRepository.findByTickerInAndTimeframeOrderByAnalyzedAtDesc(targets, "1D")
            .filter { it.price > 0 && isCrossedOnLastCandle(it) }
            .sortedBy { it.ticker }
        if (changed.isEmpty()) {
            log.info { "ReportBuilder.run: no trend change among ${targets.size} tickers — skip" }
            return null
        }

        val (golden, dead) = changed.partition { it.trend == TrendStateEnum.GOLDEN.name }
        val lines = mutableListOf(header(changed.first()))
        if (golden.isNotEmpty()) {
            lines += ""
            lines += "🟢 골든크로스 (50일선 ↑ 200일선)"
            golden.forEach { lines += formatRow(it) }
        }
        if (dead.isNotEmpty()) {
            lines += ""
            lines += "🔴 데드크로스 (50일선 ↓ 200일선)"
            dead.forEach { lines += formatRow(it) }
        }
        val full = "${lines.joinToString("\n")}\n\n$REPORT_LINK"

        val today = clock.instant().atZone(SEOUL).toLocalDate().toString()
        val payloadKey = "stock:daily-report:$today"
        val messages = split(full)
        messages.forEachIndexed { i, message ->
            val key = if (messages.size == 1) payloadKey else "$payloadKey:part-${i + 1}"
            notifyRouter.dispatch("stock", "daily-report", key, message)
        }
        return full
    }

    /** trend_since 가 분석에 쓴 마지막 일봉과 같으면 그 거래일에 교차한 것 */
    private fun isCrossedOnLastCandle(row: StockAnalysisResultEntity): Boolean =
        row.trend != TrendStateEnum.UNKNOWN.name && row.trendSince != null && row.trendSince == row.candleDate

    private fun header(row: StockAnalysisResultEntity): String {
        val nowKst = clock.instant().atZone(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " KST"
        return "<b>📊 주식 추세 전환</b> — $nowKst\n기준일 ${row.candleDate}"
    }

    /** 현재가(변동률) + 50일선 · 200일선 */
    private fun formatRow(row: StockAnalysisResultEntity): String {
        val chg = if (row.changeRate != 0.0) " (%+.1f%%)".format(row.changeRate) else ""
        val sma50 = row.technicalDetails.scalar("sma50")?.toDoubleOrNull()
        val sma200 = row.technicalDetails.scalar("sma200")?.toDoubleOrNull()
        val smaLine = "50일 ${fmtPrice(sma50)} · 200일 ${fmtPrice(sma200)}"
        return "<b>${escapeHtml(row.ticker)}</b> ${fmtPrice(row.price)}$chg  $smaLine"
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
        private const val REPORT_LINK = """<a href="https://scoophub.gonamu.com/stock">🔗 Scoophub에서 전체 보기</a>"""
        private val SEOUL = ZoneId.of("Asia/Seoul")

        fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun fmtPrice(v: Double?): String = if (v == null) "N/A" else "$%,.2f".format(v)
    }
}
