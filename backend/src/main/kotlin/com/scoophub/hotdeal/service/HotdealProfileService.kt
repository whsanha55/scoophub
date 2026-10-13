package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/** 관심도 프로필 관리. 추가·삭제하면 신선도 기간 내 항목을 즉시 재평가한다 (8.5) */
@Service
class HotdealProfileService(
    private val profileRepository: HotdealInterestProfileRepository,
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
                seedWeight = seedWeight ?: ScoringPolicy.DEFAULT_SEED_WEIGHT,
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

    companion object {
        private val WEIGHT_RANGE = ScoringPolicy.WEIGHT_MIN..ScoringPolicy.WEIGHT_MAX
    }
}
