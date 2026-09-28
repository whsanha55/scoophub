package com.scoophub.kal

import com.microsoft.playwright.Playwright
import com.scoophub.global.crawl.CrawlDataStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * legacy `kal_bonus_scraper.KalBonusScraper` — Playwright(번들 chromium)로 Akamai 를 푼 뒤
 * 동일 세션에서 in-page fetch 로 KAL API 호출.
 * Akamai 가 headless 를 차단 → headful (docker 는 xvfb 로 구동, 로컬 맥은 실제 창).
 * --disable-http2: Akamai 의 HTTP/2 스트림 리셋 회피. 순차 + 요청 간 2초 (RPS 완화).
 */
@Component
class KalBonusScraper(
    private val store: CrawlDataStore,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    data class Counts(val targets: Int, val stored: Int)

    /** (노선 × 월) 조합 크롤 → crawl_data upsert. 결과 카운트 반환 */
    fun fetchAndStore(targets: KalRoutesLoader.Targets, headless: Boolean): Counts {
        val combos = targets.routes.flatMap { (arr, _) ->
            targets.months.map { ym -> Triple(targets.departure, arr, ym) }
        }
        val raws = crawlAll(combos, headless)
        var stored = 0
        for ((combo, raw) in combos zip raws) {
            val (dep, arr, ym) = combo
            if (raw == null) {
                continue
            }
            store.upsert(
                category = KalConfig.CATEGORY,
                purpose = KalConfig.PURPOSE,
                key = KalConfig.makeKey(dep, arr, ym),
                response = raw, // API 원문 전체 저장
                dateAt = clock.instant(),
            )
            stored++
        }
        return Counts(combos.size, stored)
    }

    private fun fetchJs(): String = FETCH_JS.replace("%s", KalConfig.ENDPOINT)

    /** 단일 Chrome 세션에서 in-page fetch 루프 (순차, 요청 간 2초). 개별 실패는 스킵 */
    private fun crawlAll(combos: List<Triple<String, String, String>>, headless: Boolean): List<JsonNode?> {
        val results = MutableList<JsonNode?>(combos.size) { null }
        Playwright.create().use { playwright ->
            val browser = playwright.chromium().launch(
                com.microsoft.playwright.BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setArgs(listOf("--disable-http2", "--disable-dev-shm-usage")),
            )
            try {
                val page = browser.newPage()
                // KAL 페이지 로드 → Akamai _abck 센서 자동 풀이
                page.navigate("https://www.koreanair.com/award-seat-availability")
                page.waitForLoadState()
                for ((i, combo) in combos.withIndex()) {
                    if (i > 0) { // 첫 요청은 즉시, 이후 간격 대기
                        Thread.sleep(REQUEST_INTERVAL_MS)
                    }
                    try {
                        val raw = page.evaluate(
                            fetchJs(),
                            listOf(combo.first, combo.second, KalConfig.monthFirstDay(combo.third)),
                        )
                        results[i] = jsonMapper.valueToTree<JsonNode>(raw)
                    } catch (e: Exception) {
                        log.warn { "KAL fetch 실패 ${combo.first}-${combo.second}-${combo.third}: ${e.message}" }
                    }
                }
            } finally {
                browser.close()
            }
        }
        return results
    }

    companion object {
        private const val REQUEST_INTERVAL_MS = 2_000L

        // in-page fetch 로 호출할 JS. Akamai 풀이 완료된 page context 에서 실행되므로
        // 쿠키/센서 자동 주입. 단순 POST JSON 조회(비로그인).
        private val FETCH_JS = """
            async ([departure, arrival, dateStr]) => {
              const res = await fetch('%s', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({departureAirport: departure, arrivalAirport: arrival, departureDate: dateStr}),
              });
              if (!res.ok) {
                throw new Error('HTTP ' + res.status);
              }
              return await res.json();
            }
        """.trimIndent()
    }
}
