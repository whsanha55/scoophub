package com.scoophub.global.notify

import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.crawl.CrawlCompletedEvent
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.CrawlResult
import com.scoophub.global.crawl.repository.CrawlDataRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * legacy `core/notify/__init__.py` — 크롤 완료 발신.
 * CrawlRunner 의 [CrawlCompletedEvent] 를 비동기로 구독해 크롤을 블록하지 않는다.
 * news 는 요약 후 자체 호출한다 (스케줄러 파트).
 */
@Component
class CrawlNotifyDispatcher(
    private val props: ScoophubProperties,
    private val card: NotifyCard,
    private val router: NotifyRouter,
    private val provisioner: AutoTopicProvisioner,
    private val crawlDataRepository: CrawlDataRepository,
    private val crawlDataStore: CrawlDataStore,
    private val clock: Clock,
) {
    @Async
    @EventListener
    fun onCrawlCompleted(event: CrawlCompletedEvent) {
        dispatch(event.crawler, event.detail, event.result)
    }

    /**
     * 크롤 결과 발신.
     * - 토큰 미설정 / stock / 신규 0건 → 스킵
     * - weather → 매일 KST 7시+ 1회 (동일 날짜 재발신 방지)
     * - enrich null → 스킵 (0건/필터)
     * - payloadKey 미지정 → "{category}:{detail}:{max(new_ids) or 0}" (같은 new set 재발신 방지)
     */
    fun dispatch(category: String, detail: String, result: CrawlResult?, payloadKey: String? = null) {
        try {
            dispatchInternal(category, detail, result, payloadKey)
        } catch (e: Exception) {
            log.error { "notify dispatch failed (category=$category detail=$detail): ${e.message}" }
        }
    }

    private fun dispatchInternal(category: String, detail: String, result: CrawlResult?, payloadKey: String?) {
        if (props.telegram.botToken.isEmpty()) {
            return
        }
        if (result == null) {
            return
        }
        if (category == "stock") { // 보류 — 별도 티켓
            return
        }
        if (result.itemsNew <= 0) {
            return
        }

        // weather: 매일 KST 7시+ 1회 발신 게이트 (아침 날씨)
        var today: String? = null
        if (category == "weather") {
            val nowKst = clock.instant().atZone(SEOUL)
            if (nowKst.hour < 7) {
                return
            }
            today = nowKst.format(DateTimeFormatter.ISO_LOCAL_DATE)
            if (crawlDataRepository.findByCategoryAndPurposeAndKey("weather", "notify_sent", today) != null) {
                return // 오늘 이미 발신
            }
        }

        val newIds = result.newArticleIds
        val key = payloadKey ?: when {
            newIds.isNotEmpty() ->
                // new_article_ids 있으면 최대값으로 식별 — 같은 결과 재크롤 시 dedup
                "$category:$detail:${newIds.max()}"

            // 스냅샷 도메인은 안정 식별키 없음 — 매 run 발신 (빈 키 → dedup 미적용)
            else -> ""
        }

        val baseText = NotifyCard.formatCard(category, detail, result.itemsNew, result.itemsFetched)
        val enriched = card.enrich(category, detail, baseText, newIds) ?: return // 0건/필터 스킵

        // 라우트 부재 시 자동 토픽 생성 보장 (신규 category). 실패해도 발신/크롤은 계속.
        try {
            provisioner.ensureRoute(category, detail)
        } catch (e: Exception) {
            log.warn { "auto provision failed (category=$category detail=$detail): ${e.message}" }
        }
        router.dispatch(category, detail, key, NotifyMessage(text = enriched))

        // weather: 발신 성공 후 state upsert — 다음 run 스킵. 실패 시 state 미갱신(재시도 허용).
        if (category == "weather" && today != null) {
            try {
                crawlDataStore.upsert("weather", "notify_sent", today, mapOf<String, String>())
            } catch (e: Exception) {
                log.warn { "weather notify_sent upsert failed: ${e.message}" }
            }
        }
    }

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
    }
}
