package com.scoophub.stock

import com.scoophub.global.jackson.scalar
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
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

        val blocks = mutableListOf<String>()
        for (groupName in listOf("market", "sector", "individual")) {
            val groupTickers = groups[groupName].orEmpty()
            if (groupTickers.isEmpty()) {
                continue
            }
            buildGroupBlock(groupName, groupTickers)?.let { blocks += it }
        }
        if (blocks.isEmpty()) {
            log.info { "ReportBuilder.run: no analysis data to report — skip" }
            return null
        }

        val full = "${header()}\n\n${blocks.joinToString("\n\n")}\n\n$REPORT_LINK"
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

    /** 단일 group 1D/1W/1M 분석 블록. 빈 시 null */
    private fun buildGroupBlock(groupName: String, tickers: List<String>): String? {
        val groupTitle =
            mapOf("market" to "🌍 시장층", "sector" to "🏭 섹터층", "individual" to "📈 개별종목")[groupName] ?: groupName

        val rows1d = analysisRepository.findByTickerInAndTimeframeOrderByTotalScoreDesc(tickers, "1D")
        if (rows1d.isEmpty()) {
            return null
        }
        val auxW = auxSignalMap(tickers, "1W")
        val auxM = auxSignalMap(tickers, "1M")

        val lines = mutableListOf("<b>$groupTitle</b>")
        for (row in rows1d) {
            val price = row.price
            if (price <= 0) {
                continue
            }
            val sigmaRange = sigmaRangeFromSnapshot(row.technicalDetails.get("sigma_data"), price)
            val levels = computeActionableLevels(price, sigmaRange, row.technicalDetails)
            lines +=
                formatTicker(row.ticker, row.signal, row.totalScore, row.confidence, row.changeRate, price, auxW[row.ticker], auxM[row.ticker], levels)
        }
        if (lines.size <= 1) { // 제목만
            return null
        }
        return lines.joinToString("\n")
    }

    private fun auxSignalMap(tickers: List<String>, timeframe: String): Map<String, String> = try {
        analysisRepository.findByTickerInAndTimeframeOrderByTotalScoreDesc(tickers, timeframe)
            .associate { it.ticker to it.signal }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun header(): String {
        val nowKst = clock.instant().atZone(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " KST"
        return "<b>📊 주식 일간 분석 리포트</b>\n$nowKst"
    }

    private fun formatTicker(
        ticker: String,
        signal: String,
        score: Double,
        conf: Double,
        changeRate: Double,
        price: Double,
        auxW: String?,
        auxM: String?,
        levels: ActionableLevels?,
    ): String {
        val chg = if (changeRate != 0.0) " (%+.2f%%)".format(changeRate) else ""
        val parts = mutableListOf(
            "\n<b>${escapeHtml(ticker)}</b> ${fmtPrice(price)}$chg",
            "  시그널: $signal  점수: %+.1f  신뢰도: %.0f%%".format(score, conf),
        )
        val auxParts = buildList {
            auxW?.let { add("주봉 $it") }
            auxM?.let { add("월봉 $it") }
        }
        if (auxParts.isNotEmpty()) {
            parts += "  다기간: " + auxParts.joinToString(" / ")
        }
        if (levels != null) {
            val lvLines = buildList {
                levels.targetPrice?.let { add("목표 ${fmtPrice(it)}") }
                levels.buyZone?.let { add("매수 ${fmtPrice(it)}") }
                levels.stopLoss?.let { add("손절 ${fmtPrice(it)}") }
                if (levels.momentumFire) {
                    add("🔥불타기진입")
                }
            }
            if (lvLines.isNotEmpty()) {
                parts += "  " + lvLines.joinToString(" | ")
            }
        }
        return parts.joinToString("\n")
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
        private const val REPORT_LINK = """<a href="https://scoophub.gonamu.com/stock">🔗 Scoophub에서 보기</a>"""
        private const val STOP_ATR_MULT = 1.5
        private val SEOUL = ZoneId.of("Asia/Seoul")

        fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun fmtPrice(v: Double?): String = if (v == null) "N/A" else "$%,.2f".format(v)

        /** technical_details.sigma_data → SigmaRange. straddle 우선, WEM 폴백 */
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
            val wem = sigmaData?.get("weekly_expected_move") ?: return null
            val upper = wem.scalar("upper_1sigma")?.toDoubleOrNull() ?: return null
            val lower = wem.scalar("lower_1sigma")?.toDoubleOrNull() ?: return null
            return SigmaRange(
                center = wem.scalar("center")?.toDoubleOrNull() ?: price,
                upper1sigma = upper,
                lower1sigma = lower,
                currentPrice = price,
            )
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
