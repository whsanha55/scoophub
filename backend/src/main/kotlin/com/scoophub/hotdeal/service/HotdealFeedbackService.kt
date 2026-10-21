package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.entity.HotdealFeedItemEntity
import com.scoophub.hotdeal.entity.HotdealFeedbackEntity
import com.scoophub.hotdeal.entity.HotdealFeedbackLogEntity
import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.enums.FeedbackChannelEnum
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.repository.HotdealFeedItemRepository
import com.scoophub.hotdeal.repository.HotdealFeedbackLogRepository
import com.scoophub.hotdeal.repository.HotdealFeedbackRepository
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * 좋아요·싫어요·취소와 학습 반영 (docs/HOTDEAL_PLAN.md 8.2).
 * 피드백을 바꾸면 이 항목이 지금까지 남긴 학습 효과(로그 delta 합)를 되돌리고 새 효과를 적용한다.
 * 가중치 재평가는 다음 수집 주기에 몰아서 한다 (8.5).
 */
@Service
class HotdealFeedbackService(
    private val feedItemRepository: HotdealFeedItemRepository,
    private val feedbackRepository: HotdealFeedbackRepository,
    private val feedbackLogRepository: HotdealFeedbackLogRepository,
    private val profileRepository: HotdealInterestProfileRepository,
) {

    /** newKind == null 은 취소. 같은 피드백을 다시 보내면 학습하지 않는다. 바뀐 뒤의 피드백을 돌려준다 */
    @Transactional
    fun changeFeedback(feedItemId: Long, newKind: FeedbackKindEnum?): FeedbackKindEnum? {
        val feedItem = feedItemRepository.findByIdOrNull(feedItemId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Hotdeal feed item not found")
        val current = feedbackRepository.findByFeedItemId(feedItemId)
        val previousKind = current?.kind
        if (previousKind == newKind) {
            return newKind
        }

        val eventId = UUID.randomUUID()
        val reason = "${previousKind ?: "NONE"} -> ${newKind ?: "NONE"}"
        val netByProfile = feedbackLogRepository.sumDeltaByProfile(feedItemId).associate { it.profileId to it.delta }
        val matched = matchedProfiles(feedItem)
        val newDeltaEach = if (newKind == null || matched.isEmpty()) 0.0 else newKind.delta / matched.size
        val matchedIds = matched.mapNotNull { it.id }.toSet()
        val targets = profileRepository.findAllById(netByProfile.keys + matchedIds)

        val logs = targets.map { profile ->
            val previousWeight = profile.effectiveWeight
            val requested = -(netByProfile[profile.id] ?: 0.0) + if (profile.id in matchedIds) newDeltaEach else 0.0
            val applied = profile.applyLearned(requested, ScoringPolicy.WEIGHT_MIN, ScoringPolicy.WEIGHT_MAX)
            HotdealFeedbackLogEntity(
                feedbackEventId = eventId,
                feedItemId = feedItemId,
                profileId = profile.id,
                previousKind = previousKind,
                newKind = newKind,
                delta = applied,
                previousWeight = previousWeight,
                newWeight = profile.effectiveWeight,
                reason = reason,
            )
        }.ifEmpty {
            // 매칭된 프로필 항목이 없어도 변경 이력은 1건 남긴다
            listOf(
                HotdealFeedbackLogEntity(
                    feedbackEventId = eventId,
                    feedItemId = feedItemId,
                    profileId = null,
                    previousKind = previousKind,
                    newKind = newKind,
                    delta = 0.0,
                    previousWeight = null,
                    newWeight = null,
                    reason = reason,
                ),
            )
        }
        feedbackLogRepository.saveAll(logs)

        when {
            newKind == null -> feedbackRepository.delete(requireNotNull(current))

            current == null -> feedbackRepository.save(
                HotdealFeedbackEntity(feedItem = feedItem, kind = newKind, channel = FeedbackChannelEnum.WEB),
            )

            else -> current.change(newKind)
        }
        log.info { "hotdeal feedback - feedItemId=$feedItemId, $reason, profiles=${targets.size}" }
        return newKind
    }

    /** 추천 근거(matched_terms.terms)의 (kind, value) 와 같은 현재 프로필 항목 */
    private fun matchedProfiles(feedItem: HotdealFeedItemEntity): List<HotdealInterestProfileEntity> {
        val termNodes: Iterable<JsonNode> = feedItem.matchedTerms.path("terms")
        val keys = termNodes.map { it.path("kind").asText() to it.path("value").asText() }.toSet()
        if (keys.isEmpty()) {
            return emptyList()
        }
        return profileRepository.findAll().filter { (it.kind.name to it.value) in keys }
    }
}
