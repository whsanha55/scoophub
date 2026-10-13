package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.entity.HotdealFeedItemEntity
import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.entity.HotdealItemEntity
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealFeedItemRepository
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.repository.HotdealItemRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import kotlin.math.min

private val log = KotlinLogging.logger {}

@Service
class HotdealScoringService(
    private val itemRepository: HotdealItemRepository,
    private val profileRepository: HotdealInterestProfileRepository,
    private val feedItemRepository: HotdealFeedItemRepository,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    private class TermContribution(val kind: ProfileKindEnum, val value: String, val weight: Double)

    private class ScoreResult(val terms: List<TermContribution>, val popularityBonus: Double, val score: Double)

    /** 신선도 기간 내 원본을 현재 프로필로 다시 평가한다 (8.5) */
    @Transactional
    fun evaluateRecentItems() {
        val now = clock.instant()
        val profiles = profileRepository.findAll()
        val candidates = itemRepository.findByItemAtAfter(now.minus(ScoringPolicy.FRESHNESS))
        val existingByItemId = feedItemRepository
            .findAllByItemIds(candidates.mapNotNull { it.id })
            .associateByTo(mutableMapOf()) { it.item.id }

        val evaluatedIds = mutableSetOf<Long?>()
        for (item in candidates) {
            val result = score(item, profiles) ?: continue
            existingByItemId[item.id] = upsert(item, result, existingByItemId[item.id], popular = false, now)
            evaluatedIds += item.id
        }
        val recommended = evaluatedIds.size
        evaluatedIds += evaluatePopularItems(candidates, existingByItemId, profiles, evaluatedIds.toSet(), now)

        // 이번 평가에서 추천 조건을 벗어난 기존 항목은 숨긴다 (8.5). 피드백 이력이 걸려 있어 삭제하지 않는다.
        val deactivated = existingByItemId
            .filter { (itemId, feedItem) -> itemId !in evaluatedIds && feedItem.active }
            .onEach { (_, feedItem) -> feedItem.deactivate(now) }
            .size
        log.info {
            "hotdeal scoring done - candidates=${candidates.size}, recommended=$recommended, " +
                "popular=${evaluatedIds.size - recommended}, deactivated=$deactivated"
        }
    }

    /** 콜드스타트: 관심 항목이 부족한 동안 인기 상위를 함께 노출한다 (8.3). 노출한 원본 id 를 돌려준다 */
    private fun evaluatePopularItems(
        candidates: List<HotdealItemEntity>,
        existingByItemId: MutableMap<Long?, HotdealFeedItemEntity>,
        profiles: List<HotdealInterestProfileEntity>,
        keywordRecommendedIds: Set<Long?>,
        now: Instant,
    ): Set<Long?> {
        val interestCount = profiles.count { it.kind != ProfileKindEnum.SOURCE }
        if (interestCount >= ScoringPolicy.COLDSTART_PROFILE_MIN) {
            return emptySet()
        }
        val topPopular = candidates
            .sortedByDescending(::votesOf)
            .take(ScoringPolicy.POPULAR_FEED_SIZE)

        val popularIds = mutableSetOf<Long?>()
        for (item in topPopular) {
            // 이번 평가에서 키워드 추천된 항목은 그 이유를 유지한다.
            if (item.id in keywordRecommendedIds) {
                continue
            }
            val existing = existingByItemId[item.id]
            val bonus = popularityBonus(item)
            val result = ScoreResult(terms = emptyList(), popularityBonus = bonus, score = bonus)
            existingByItemId[item.id] = upsert(item, result, existing, popular = true, now)
            popularIds += item.id
        }
        return popularIds
    }

    /** 관심 키워드·카테고리가 하나 이상 매칭되고 총점이 임계치 이상이어야 추천한다 (8.1) */
    private fun score(item: HotdealItemEntity, profiles: List<HotdealInterestProfileEntity>): ScoreResult? {
        val matchedKeywords = profiles
            .filter { it.kind == ProfileKindEnum.KEYWORD && KeywordMatcher.matches(item.title, it.value) }
            .map { TermContribution(it.kind, it.value, it.effectiveWeight) }
        val matchedCategories = profiles
            .filter {
                it.kind == ProfileKindEnum.CATEGORY &&
                    item.category != null &&
                    it.value.equals(item.category, ignoreCase = true)
            }
            .map { TermContribution(it.kind, it.value, it.effectiveWeight) }
        val sourceWeight = profiles
            .filter { it.kind == ProfileKindEnum.SOURCE && it.value == item.source.name }
            .sumOf { it.effectiveWeight }

        val matchedTerms = matchedKeywords + matchedCategories
        val popularityBonus = popularityBonus(item)
        val total = matchedTerms.sumOf { it.weight } + sourceWeight + popularityBonus
        return when {
            matchedTerms.isEmpty() -> null
            total < ScoringPolicy.WEB_THRESHOLD -> null
            else -> ScoreResult(matchedTerms, popularityBonus, total)
        }
    }

    private fun upsert(
        item: HotdealItemEntity,
        result: ScoreResult,
        existing: HotdealFeedItemEntity?,
        popular: Boolean,
        now: Instant,
    ): HotdealFeedItemEntity {
        val matchedTerms = matchedTermsOf(result, popular)
        if (existing == null) {
            return feedItemRepository.save(
                HotdealFeedItemEntity(
                    item = item,
                    score = result.score,
                    matchedTerms = matchedTerms,
                    popular = popular,
                    scoringVersion = ScoringPolicy.SCORING_VERSION,
                    recommendedAt = now,
                    evaluatedAt = now,
                    expiresAt = item.itemAt.plus(ScoringPolicy.FRESHNESS),
                ),
            )
        }
        existing.updateEvaluation(result.score, matchedTerms, popular, now)
        return existing
    }

    private fun matchedTermsOf(result: ScoreResult, popular: Boolean): JsonNode = jsonMapper.valueToTree(
        mapOf(
            "reason" to reasonOf(result, popular),
            "terms" to result.terms.map { mapOf("kind" to it.kind.name, "value" to it.value, "weight" to it.weight) },
            "popularity_bonus" to result.popularityBonus,
        ),
    )

    private fun reasonOf(result: ScoreResult, popular: Boolean): String {
        if (popular) {
            return "인기 항목"
        }
        return "관심 매칭(" + result.terms.joinToString(", ") { it.value } + ")"
    }

    private fun popularityBonus(item: HotdealItemEntity): Double =
        min(votesOf(item) * ScoringPolicy.POPULARITY_COEFFICIENT, ScoringPolicy.POPULARITY_CAP)

    private fun votesOf(item: HotdealItemEntity): Long = item.extra.path("votes").asLong(0L)
}
