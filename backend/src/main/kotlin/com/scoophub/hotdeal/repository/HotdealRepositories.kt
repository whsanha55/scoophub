package com.scoophub.hotdeal.repository

import com.scoophub.hotdeal.entity.HotdealFeedItemEntity
import com.scoophub.hotdeal.entity.HotdealFeedbackEntity
import com.scoophub.hotdeal.entity.HotdealFeedbackLogEntity
import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.entity.HotdealItemEntity
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
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
     * 기본 피드: 피드백 없는 추천 활성 항목 + 좋아요한 항목(점수와 무관하게 유지).
     * 싫어요 항목은 제외한다. 피드백은 항목당 1건이다.
     */
    @Query(
        value = """
            SELECT f FROM HotdealFeedItemEntity f JOIN FETCH f.item
            LEFT JOIN HotdealFeedbackEntity b ON b.feedItem = f
            WHERE (b.id IS NULL AND f.active = true)
               OR b.kind = com.scoophub.hotdeal.enums.FeedbackKindEnum.LIKE
        """,
        countQuery = """
            SELECT COUNT(f) FROM HotdealFeedItemEntity f
            LEFT JOIN HotdealFeedbackEntity b ON b.feedItem = f
            WHERE (b.id IS NULL AND f.active = true)
               OR b.kind = com.scoophub.hotdeal.enums.FeedbackKindEnum.LIKE
        """,
    )
    fun findDefaultFeed(pageable: Pageable): Page<HotdealFeedItemEntity>

    /** 피드백 종류별 모아보기 (DISLIKE = 숨긴 항목, LIKE = 좋아요 목록) */
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
}
