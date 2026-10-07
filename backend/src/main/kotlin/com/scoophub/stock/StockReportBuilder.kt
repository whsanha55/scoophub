package com.scoophub.stock

import com.scoophub.global.jackson.scalar
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.stock.entity.StockAnalysisResultEntity
import com.scoophub.stock.repository.StockAnalysisResultRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.vo.SigmaRange
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/** 액션러블 거래 레벨. 산출 불가 시 null */
data class ActionableLevels(
    val targetPrice: Double?, // 목표가 (+1σ or BB 상단)
    val buyZone: Double?, // 매수 구간 (-1σ or BB 하단)
    val stopLoss: Double?, // 손절가 (진입가 - 1.5×ATR)
    val momentumFire: Boolean = false, // 불타기 진입 (price>EMA12 & MACD hist>0)
)

/** legacy `stock/report.py` — 분석 결과 기반 일간 리포트 조립 + 발신 연쇄 */
@Component
class StockReportBuilder(
    private val analysisRepository: StockAnalysisResultRepository,
    private val watchlistRepository: StockWatchlistRepository,
    private val notifyRouter: NotifyRouter,
    private val clock: Clock,
) {
    /** 리포트 빌드 + 발신. 빈 데이터 시 null 반환(발신 스킵) */
    fun run(tickers: List<String>? = null): String? {
        val groups = resolveGroups(tickers)
        if (groups.isEmpty()) {
            log.info { "ReportBuilder.run: no watchlist items — skip report" }
            return null
        }

        val rows1d = groups.mapValues { (_, groupTickers) ->
            analysisRepository.findByTickerInAndTimeframeOrderByTotalScoreDesc(groupTickers, "1D")
        }
        if (rows1d.values.all { it.isEmpty() }) {
            log.info { "ReportBuilder.run: no analysis data to report — skip" }
            return null
        }

        val blocks = mutableListOf(header(groups["market"].orEmpty(), rows1d["market"].orEmpty()))
        for ((groupName, title) in listOf("sector" to "🏭 섹터", "individual" to "📈 개별종목")) {
            val groupTickers = groups[groupName].orEmpty()
            if (groupTickers.isEmpty()) {
                continue
            }
            blocks += buildSignalBlock(title, groupTickers, rows1d[groupName].orEmpty())
        }

        val full = "${blocks.joinToString("\n\n")}\n\n$REPORT_LINK"
        val today = clock.instant().atZone(SEOUL).toLocalDate().toString()
        val payloadKey = "stock:daily-report:$today"

        val messages = split(full)
        messages.forEachIndexed { i, message ->
            val key = if (messages.size == 1) payloadKey else "$payloadKey:part-${i + 1}"
            notifyRouter.dispatch("stock", "daily-report", key, message)
        }
        return full
    }

    /** group → ticker list. tickers 지정 시 해당 티커의 group 사용 */
    private fun resolveGroups(tickers: List<String>?): Map<String, List<String>> {
        if (tickers != null) {
            val result = mutableMapOf<String, MutableList<String>>()
            for (t in tickers) {
                val grp = watchlistRepository.findByTickerAndIsActive(t.uppercase())?.group?.ifEmpty { null }
                    ?: "individual"
                result.getOrPut(grp) { mutableListOf() }.add(t.uppercase())
            }
            return result
        }
        val groups = mutableMapOf<String, List<String>>()
        for (grp in listOf("market", "sector", "individual")) {
            val items = watchlistRepository.findByIsActiveAndGroupOrderByAddedAt(true, grp)
            if (items.isNotEmpty()) {
                groups[grp] = items.map { it.ticker }
            }
        }
        return groups
    }

    /** 1D·1W 같은 방향 + 1D 신뢰도 기준 이상 종목만 매수/매도로 나눈 블록 */
    private fun buildSignalBlock(
        title: String,
        tickers: List<String>,
        rows1d: List<StockAnalysisResultEntity>,
    ): String {
        val auxW = auxSignalMap(tickers, "1W")
        val picked = rows1d
            .filter { it.price > 0 && it.confidence >= MIN_CONFIDENCE }
            .filter { direction(it.signal) != 0 && direction(it.signal) == direction(auxW[it.ticker]) }
            .sortedByDescending { it.confidence }
        val (buys, sells) = picked.partition { direction(it.signal) > 0 }

        val lines = mutableListOf("<b>$title</b>")
        if (picked.isEmpty()) {
            lines += "강한 신호 없음"
        }
        if (buys.isNotEmpty()) {
            lines += "🟢 매수"
            buys.forEach { lines += formatBuy(it) }
        }
        if (sells.isNotEmpty()) {
            lines += "🔴 매도"
            sells.forEach { lines += formatSell(it) }
        }
        return lines.joinToString("\n")
    }

    private fun auxSignalMap(tickers: List<String>, timeframe: String): Map<String, String> = try {
        analysisRepository.findByTickerInAndTimeframeOrderByTotalScoreDesc(tickers, timeframe)
            .associate { it.ticker to it.signal }
    } catch (e: Exception) {
        emptyMap()
    }

    /** 제목 + 시장층 1D 신호 한 줄 (STRONG 구분 없이) */
    private fun header(marketTickers: List<String>, marketRows: List<StockAnalysisResultEntity>): String {
        val nowKst = clock.instant().atZone(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " KST"
        val title = "<b>📊 주식 일간 신호</b> — $nowKst"
        val signals = marketTickers.mapNotNull { t ->
            marketRows.find { it.ticker == t }?.let { "${escapeHtml(t)} ${it.signal.removePrefix("STRONG_")}" }
        }
        return if (signals.isEmpty()) title else "$title\n시장: ${signals.joinToString(" · ")}"
    }

    /** 매수: 현재가(변동률) + 목표·손절 */
    private fun formatBuy(row: StockAnalysisResultEntity): String {
        val sigmaRange = sigmaRangeFromSnapshot(row.technicalDetails.get("sigma_data"), row.price)
        val levels = computeActionableLevels(row.price, sigmaRange, row.technicalDetails)
        val lvParts = listOfNotNull(
            levels?.targetPrice?.let { "목표 ${fmtPrice(it)}" },
            levels?.stopLoss?.let { "손절 ${fmtPrice(it)}" },
        )
        return listOf(priceLine(row), lvParts.joinToString(" · ")).filter { it.isNotEmpty() }.joinToString("  ")
    }

    /** 매도: 현재가(변동률) + 재진입가(볼린저 하단) */
    private fun formatSell(row: StockAnalysisResultEntity): String {
        val reentry = row.technicalDetails.scalar("bb_lower")?.toDoubleOrNull()
            ?: return priceLine(row)
        return "${priceLine(row)}  재진입 ${fmtPrice(reentry)}"
    }

    private fun priceLine(row: StockAnalysisResultEntity): String {
        val chg = if (row.changeRate != 0.0) " (%+.1f%%)".format(row.changeRate) else ""
        return "<b>${escapeHtml(row.ticker)}</b> ${fmtPrice(row.price)}$chg"
    }

    /** BUY 계열 1, SELL 계열 -1, 그 외 0 */
    private fun direction(signal: String?): Int = when {
        signal == null -> 0
        signal.endsWith("BUY") -> 1
        signal.endsWith("SELL") -> -1
        else -> 0
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
        private const val MIN_CONFIDENCE = 60.0
        private const val REPORT_LINK = """<a href="https://scoophub.gonamu.com/stock">🔗 Scoophub에서 전체 보기</a>"""
        private const val STOP_ATR_MULT = 1.5
        private val SEOUL = ZoneId.of("Asia/Seoul")

        fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun fmtPrice(v: Double?): String = if (v == null) "N/A" else "$%,.2f".format(v)

        /** technical_details.sigma_data.straddle → SigmaRange (없으면 null, BB 폴백은 computeActionableLevels 담당) */
        fun sigmaRangeFromSnapshot(sigmaData: JsonNode?, price: Double): SigmaRange? {
            val em = sigmaData?.get("straddle")?.scalar("expected_move")?.toDoubleOrNull()
            if (em != null && em > 0) {
                return SigmaRange(
                    center = price,
                    upper1sigma = price + em,
                    lower1sigma = price - em,
                    currentPrice = price,
                )
            }
            return null
        }

        /** sigma ±1σ 우선, 부재 시 BB 밴드 폴백으로 액션러블 레벨 산출 */
        fun computeActionableLevels(price: Double, sigmaRange: SigmaRange?, techDetails: JsonNode?): ActionableLevels? {
            if (price <= 0) {
                return null
            }
            val upper1 = sigmaRange?.upper1sigma
            val lower1 = sigmaRange?.lower1sigma

            val atrVal = techDetails?.scalar("atr")?.toDoubleOrNull() ?: 0.0
            val ema12 = techDetails?.scalar("ema12")?.toDoubleOrNull() ?: 0.0
            val macdHist = techDetails?.scalar("macd_histogram")?.toDoubleOrNull() ?: 0.0
            val bbUpper = techDetails?.scalar("bb_upper")?.toDoubleOrNull()
            val bbLower = techDetails?.scalar("bb_lower")?.toDoubleOrNull()

            val target = upper1 ?: bbUpper
            val buy = lower1 ?: bbLower
            if (target == null && buy == null) {
                return null
            }

            // 불타기 진입: price>EMA12 & MACD hist>0 (상승 모멘텀)
            val fire = ema12 != 0.0 && macdHist > 0 && price > ema12

            // 손절 기준 = 진입가 (momentum_fire 면 현재가, 아니면 매수구간)
            var stopLoss: Double? = null
            if (atrVal > 0) {
                val basis = if (fire) price else buy
                if (basis != null) {
                    stopLoss = basis - STOP_ATR_MULT * atrVal
                }
            }
            return ActionableLevels(target, buy, stopLoss, fire)
        }
    }
}
