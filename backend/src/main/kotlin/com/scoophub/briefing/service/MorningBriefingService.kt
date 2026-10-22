package com.scoophub.briefing.service

import com.scoophub.briefing.MorningBriefing
import com.scoophub.briefing.repository.MorningBriefingQueryRepository
import com.scoophub.briefing.vo.MorningBriefingSettings
import com.scoophub.global.crawl.CrawlDataStore
import com.scoophub.global.crawl.repository.CrawlDataRepository
import com.scoophub.global.schedule.CronTriggers
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 아침 브리핑 후보 조회·구성과 "이미 나온 항목" 기록.
 * 이미 나온 항목은 crawl_data(briefing, briefed, {묶음}:{키}) 로 남긴다.
 */
@Service
class MorningBriefingService(
    private val queryRepository: MorningBriefingQueryRepository,
    private val crawlDataRepository: CrawlDataRepository,
    private val crawlDataStore: CrawlDataStore,
) {

    /** 보낼 것이 없으면 null */
    fun compose(settings: MorningBriefingSettings, now: Instant): MorningBriefing? {
        val since = now.minus(settings.lookback)
        val candidates = queryRepository.findNewsCandidates(since) +
            queryRepository.findHackerNewsCandidates(since) +
            queryRepository.findGithubCandidates(since) +
            queryRepository.findDevtoCandidates(since)
        val briefedKeys = crawlDataRepository
            .findByCategoryAndPurposeAndKeyIn(CATEGORY, PURPOSE, candidates.map { it.briefingKey })
            .map { it.key }
            .toSet()
        return MorningBriefing.compose(now.atZone(CronTriggers.SEOUL).toLocalDate(), candidates, briefedKeys, settings)
    }

    @Transactional
    fun markBriefed(briefingKeys: List<String>, now: Instant) {
        briefingKeys.forEach {
            crawlDataStore.upsert(CATEGORY, PURPOSE, it, mapOf("briefed_at" to now.toString()), now)
        }
    }

    companion object {
        private const val CATEGORY = "briefing"
        private const val PURPOSE = "briefed"
    }
}
