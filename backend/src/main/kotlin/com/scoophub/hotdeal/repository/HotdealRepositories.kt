package com.scoophub.hotdeal.repository

import com.scoophub.hotdeal.entity.HotdealFeedItemEntity
import com.scoophub.hotdeal.entity.HotdealFeedbackEntity
import com.scoophub.hotdeal.entity.HotdealFeedbackLogEntity
import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.entity.HotdealItemEntity
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.vo.ItemProfileDeltaRow
import com.scoophub.hotdeal.vo.ProfileDeltaRow
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface HotdealItemRepository : JpaRepository<HotdealItemEntity, Long> {
    fun findByItemAtAfter(itemAt: Instant): List<HotdealItemEntity>
}

interface HotdealInterestProfileRepository : JpaRepository<HotdealInterestProfileEntity, Long> {
    fun existsByKindAndValue(kind: ProfileKindEnum, value: String): Boolean
}

interface HotdealFeedItemRepository : JpaRepository<HotdealFeedItemEntity, Long> {

    @Query("SELECT f FROM HotdealFeedItemEntity f WHERE f.item.id IN :itemIds")
    fun findAllByItemIds(itemIds: Collection<Long>): List<HotdealFeedItemEntity>

    /**
     * 기본 피드: 피드백 없는 추천 활성 항목 중 판정 통과 항목 + 좋아요한 항목(점수·판정과 무관하게 유지).
     * 싫어요 항목은 제외한다. 피드백은 항목당 1건이다.
     */
    @Query(
        value = """
            SELECT f FROM HotdealFeedItemEntity f JOIN FETCH f.item
            LEFT JOIN HotdealFeedbackEntity b ON b.feedItem = f
            WHERE (b.id IS NULL AND f.active = true AND f.judgedOut = false)
               OR b.kind = com.scoophub.hotdeal.enums.FeedbackKindEnum.LIKE
        """,
        countQuery = """
            SELECT COUNT(f) FROM HotdealFeedItemEntity f
            LEFT JOIN HotdealFeedbackEntity b ON b.feedItem = f
            WHERE (b.id IS NULL AND f.active = true AND f.judgedOut = false)
               OR b.kind = com.scoophub.hotdeal.enums.FeedbackKindEnum.LIKE
        """,
    )
    fun findDefaultFeed(pageable: Pageable): Page<HotdealFeedItemEntity>

    /** 숨긴 항목: 싫어요한 항목 + 피드백 없이 관련 없음·제외로 판정된 추천 항목 */
    @Query(
        value = """
            SELECT f FROM HotdealFeedItemEntity f JOIN FETCH f.item
            LEFT JOIN HotdealFeedbackEntity b ON b.feedItem = f
            WHERE b.kind = com.scoophub.hotdeal.enums.FeedbackKindEnum.DISLIKE
               OR (b.id IS NULL AND f.active = true AND f.judgedOut = true)
        """,
        countQuery = """
            SELECT COUNT(f) FROM HotdealFeedItemEntity f
            LEFT JOIN HotdealFeedbackEntity b ON b.feedItem = f
            WHERE b.kind = com.scoophub.hotdeal.enums.FeedbackKindEnum.DISLIKE
               OR (b.id IS NULL AND f.active = true AND f.judgedOut = true)
        """,
    )
    fun findHiddenFeed(pageable: Pageable): Page<HotdealFeedItemEntity>

    /** 좋아요 목록 */
    @Query(
        value = """
            SELECT f FROM HotdealFeedItemEntity f JOIN FETCH f.item
            WHERE EXISTS (SELECT 1 FROM HotdealFeedbackEntity b WHERE b.feedItem = f AND b.kind = :kind)
        """,
        countQuery = """
            SELECT COUNT(f) FROM HotdealFeedItemEntity f
            WHERE EXISTS (SELECT 1 FROM HotdealFeedbackEntity b WHERE b.feedItem = f AND b.kind = :kind)
        """,
    )
    fun findFeedByFeedbackKind(kind: FeedbackKindEnum, pageable: Pageable): Page<HotdealFeedItemEntity>

    /**
     * 알림 후보 (9.1): 피드백 없고, 판정을 거쳐 통과했고, 콜드스타트 인기 항목이 아니고,
     * 아직 보내지 않은 최근 게시물 중 점수가 알림 임계치 이상인 항목. 점수 높은 순
     */
    @Query(
        """
        SELECT f FROM HotdealFeedItemEntity f JOIN FETCH f.item i
        LEFT JOIN HotdealFeedbackEntity b ON b.feedItem = f
        WHERE b.id IS NULL AND f.active = true AND f.popular = false
          AND f.judgment IS NOT NULL AND f.judgedOut = false AND f.notifiedAt IS NULL
          AND f.score >= :threshold AND i.itemAt > :since
        ORDER BY f.score DESC
        """,
    )
    fun findNotifyCandidates(threshold: Double, since: Instant): List<HotdealFeedItemEntity>

    fun countByNotifiedAtGreaterThanEqual(since: Instant): Long

    /** 판정 후보: 신선도 기간 안의 추천 활성 항목 */
    @Query("SELECT f FROM HotdealFeedItemEntity f JOIN FETCH f.item WHERE f.active = true AND f.expiresAt > :now")
    fun findActiveWithItem(now: Instant): List<HotdealFeedItemEntity>
}

interface HotdealFeedbackRepository : JpaRepository<HotdealFeedbackEntity, Long> {

    @Query("SELECT b FROM HotdealFeedbackEntity b WHERE b.feedItem.id = :feedItemId")
    fun findByFeedItemId(feedItemId: Long): HotdealFeedbackEntity?

    @Query("SELECT b FROM HotdealFeedbackEntity b WHERE b.feedItem.id IN :feedItemIds")
    fun findAllByFeedItemIds(feedItemIds: Collection<Long>): List<HotdealFeedbackEntity>
}

interface HotdealFeedbackLogRepository : JpaRepository<HotdealFeedbackLogEntity, Long> {

    /** 항목의 피드백이 지금까지 프로필별로 남긴 학습 효과 (실제 적용 delta 합) */
    @Query(
        """
        SELECT l.profileId AS profileId, SUM(l.delta) AS delta
        FROM HotdealFeedbackLogEntity l
        WHERE l.feedItemId = :feedItemId AND l.profileId IS NOT NULL
        GROUP BY l.profileId
        """,
    )
    fun sumDeltaByProfile(feedItemId: Long): List<ProfileDeltaRow>

    /** 모든 항목·프로필별 현재 학습 효과. 학습값 초기화 때 보정 로그를 남기는 데 쓴다 */
    @Query(
        """
        SELECT l.feedItemId AS feedItemId, l.profileId AS profileId, SUM(l.delta) AS delta
        FROM HotdealFeedbackLogEntity l
        WHERE l.profileId IS NOT NULL
        GROUP BY l.feedItemId, l.profileId
        """,
    )
    fun sumDeltaByItemAndProfile(): List<ItemProfileDeltaRow>
}
