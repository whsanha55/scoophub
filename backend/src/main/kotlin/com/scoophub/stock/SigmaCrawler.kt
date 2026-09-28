package com.scoophub.stock

import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.Crawler
import com.scoophub.stock.repository.StockWeeklyExpectedMoveRepository
import com.scoophub.stock.vo.WeeklyExpectedMove
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/** legacy `stock/crawler.py` — usstocksigma.com 주간 예상움직임 테이블 스크래핑 (Jsoup) */
@Component
class SigmaCrawler(private val wemRepository: StockWeeklyExpectedMoveRepository) : Crawler {
    override val name = "stock"
    override val detail = "sigma-scan"

    override fun fetch(): CrawlResult {
        log.info { "SigmaCrawler.fetch() 시작 — $BASE_URL" }
        val doc = Jsoup.connect(BASE_URL)
            .userAgent(UA)
            .timeout(30_000)
            .get()

        // h1.entry-title 에서 만기일(Exp) 추출
        val expiryDate = doc.selectFirst("h1.entry-title")
            ?.text()
            ?.let { EXP_REGEX.find(it)?.value?.substringAfter(":")?.trim() }
            ?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ofPattern("MM/dd/yyyy")) }.getOrNull() }

        // CSS class 'expected-move-table' 테이블 각각 파싱 — 컬럼: Ticker | Price | % | -1σ | +1σ
        val results = mutableListOf<WeeklyExpectedMove>()
        for (table in doc.select("table.expected-move-table")) {
            for (row in table.select("tr").drop(1)) {
                val cols = row.select("td")
                if (cols.size < 5) {
                    continue
                }
                val ticker = cols[0].text().trim()
                val emPct = parseFloat(cols[2].text())
                val emLow = parseFloat(cols[3].text())
                val emHigh = parseFloat(cols[4].text())
                val expiry = expiryDate ?: continue // 만기일 없으면 week 계산 불가 — 스킵
                if (ticker.isNotEmpty() && emHigh > 0) {
                    results += WeeklyExpectedMove(
                        ticker = ticker,
                        weekStart = expiry.minusDays(7),
                        weekEnd = expiry,
                        expectedMoveHigh = emHigh,
                        expectedMoveLow = emLow,
                        expectedMovePct = emPct,
                    )
                }
            }
        }

        if (results.isEmpty()) {
            log.info { "SigmaCrawler.fetch() 완료 — 파싱 결과 없음" }
            return CrawlResult()
        }
        val itemsNew = saveBatch(results)
        log.info { "SigmaCrawler.fetch() 완료 — fetched=${results.size}, new=$itemsNew" }
        return CrawlResult(itemsFetched = results.size, itemsNew = itemsNew)
    }

    @Transactional
    fun saveBatch(items: List<WeeklyExpectedMove>): Int {
        var count = 0
        for (wem in items) {
            wemRepository.upsert(
                wem.ticker,
                wem.weekStart,
                wem.weekEnd,
                wem.expectedMoveHigh,
                wem.expectedMoveLow,
                wem.expectedMovePct,
            )
            count++
        }
        return count
    }

    companion object {
        private const val BASE_URL = "https://usstocksigma.com"
        private const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val EXP_REGEX = Regex("""Exp\s*:\s*\d{2}/\d{2}/\d{4}""")

        fun parseFloat(text: String): Double = text.replace(Regex("[,$%]"), "").trim().toDoubleOrNull() ?: 0.0
    }
}
