package com.scoophub.hotdeal.facade

import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.service.HotdealProfileService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 프로필 변경(서비스 트랜잭션 안에서 즉시 재평가) 뒤 바뀐 판정 입력을 트랜잭션 밖에서 다시 판정한다.
 * 판정이 실패해도 프로필 변경은 유지되고, 다음 수집 주기에 다시 판정한다.
 */
@Component
class HotdealProfileFacade(
    private val profileService: HotdealProfileService,
    private val judgmentFacade: HotdealJudgmentFacade,
) {

    fun create(kind: ProfileKindEnum, value: String, seedWeight: Double?): HotdealInterestProfileEntity =
        profileService.create(kind, value, seedWeight).also { judgeQuietly() }

    fun changeSeedWeight(profileId: Long, seedWeight: Double): HotdealInterestProfileEntity =
        profileService.changeSeedWeight(profileId, seedWeight).also { judgeQuietly() }

    fun delete(profileId: Long) {
        profileService.delete(profileId)
        judgeQuietly()
    }

    fun resetLearned() {
        profileService.resetLearned()
        judgeQuietly()
    }

    private fun judgeQuietly() {
        try {
            judgmentFacade.judgePending()
        } catch (e: Exception) {
            log.warn(e) { "hotdeal judgment after profile change failed, retry next cycle: ${e.message}" }
        }
    }
}
