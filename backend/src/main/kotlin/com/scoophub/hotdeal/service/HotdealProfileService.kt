package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.entity.HotdealFeedbackLogEntity
import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealFeedbackLogRepository
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** 관심도 프로필 관리. 바꾸면 신선도 기간 내 항목을 즉시 재평가한다 (8.5). 판정은 HotdealProfileFacade 가 이어서 한다 */
@Service
class HotdealProfileService(
    private val profileRepository: HotdealInterestProfileRepository,
    private val feedbackLogRepository: HotdealFeedbackLogRepository,
    private val scoringService: HotdealScoringService,
) {

    fun findAll(): List<HotdealInterestProfileEntity> = profileRepository.findAll(Sort.by("kind", "value"))

    @Transactional
    fun create(kind: ProfileKindEnum, value: String, seedWeight: Double?): HotdealInterestProfileEntity {
        val normalized = value.trim()
        if (normalized.isEmpty() || (seedWeight != null && seedWeight !in WEIGHT_RANGE)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid hotdeal profile")
        }
        if (profileRepository.existsByKindAndValue(kind, normalized)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Hotdeal profile already exists")
        }
        val profile = profileRepository.save(
            HotdealInterestProfileEntity(
                kind = kind,
                value = normalized,
                // 제외 키워드는 점수에 쓰지 않는다
                seedWeight = if (kind ==
                    ProfileKindEnum.EXCLUDE
                ) {
                    0.0
                } else {
                    seedWeight ?: ScoringPolicy.DEFAULT_SEED_WEIGHT
                },
            ),
        )
        scoringService.evaluateRecentItems()
        return profile
    }

    @Transactional
    fun delete(profileId: Long) {
        val profile = profileRepository.findByIdOrNull(profileId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Hotdeal profile not found")
        profileRepository.delete(profile)
        profileRepository.flush()
        scoringService.evaluateRecentItems()
    }

    @Transactional
    fun changeSeedWeight(profileId: Long, seedWeight: Double): HotdealInterestProfileEntity {
        val profile = profileRepository.findByIdOrNull(profileId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Hotdeal profile not found")
        if (profile.kind == ProfileKindEnum.EXCLUDE || seedWeight !in WEIGHT_RANGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid hotdeal profile weight")
        }
        profile.changeSeedWeight(seedWeight)
        scoringService.evaluateRecentItems()
        return profile
    }

    /**
     * 학습값을 모두 0 으로 되돌린다. 항목별 학습 효과(로그 delta 합)도 0 이 되도록 보정 로그를 남겨,
     * 이후 피드백을 바꾸거나 취소할 때 이미 사라진 효과를 다시 빼지 않게 한다. 피드백 상태는 유지한다.
     */
    @Transactional
    fun resetLearned() {
        val eventId = UUID.randomUUID()
        val compensations = feedbackLogRepository.sumDeltaByItemAndProfile()
            .filter { it.delta != 0.0 }
            .map {
                HotdealFeedbackLogEntity(
                    feedbackEventId = eventId,
                    feedItemId = it.feedItemId,
                    profileId = it.profileId,
                    previousKind = null,
                    newKind = null,
                    delta = -it.delta,
                    previousWeight = null,
                    newWeight = null,
                    reason = "RESET_LEARNED",
                )
            }
        feedbackLogRepository.saveAll(compensations)
        profileRepository.findAll().forEach { it.resetLearned() }
        scoringService.evaluateRecentItems()
    }

    companion object {
        private val WEIGHT_RANGE = ScoringPolicy.WEIGHT_MIN..ScoringPolicy.WEIGHT_MAX
    }
}
