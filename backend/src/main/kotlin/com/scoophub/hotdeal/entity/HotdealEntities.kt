package com.scoophub.hotdeal.entity

import com.scoophub.hotdeal.enums.FeedbackChannelEnum
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.HotdealSourceEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** V42 hotdeal_item — 조회 전용. 저장은 HotdealItemQueryRepository.upsert 가 담당한다 */
@Entity
@Table(name = "hotdeal_item")
class HotdealItemEntity(
    @Enumerated(EnumType.STRING)
    val source: HotdealSourceEnum,
    val provider: String,
    val sourceKey: String,
    val category: String?,
    val title: String,
    val url: String,
    @JdbcTypeCode(SqlTypes.JSON)
    val extra: JsonNode,
    val itemAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

/** V43 hotdeal_interest_profile — 유효 가중치는 seed_weight + learned_weight */
@Entity
@Table(name = "hotdeal_interest_profile")
class HotdealInterestProfileEntity(
    @Enumerated(EnumType.STRING)
    val kind: ProfileKindEnum,
    val value: String,
    seedWeight: Double,
    learnedWeight: Double = 0.0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var seedWeight: Double = seedWeight
        protected set

    var learnedWeight: Double = learnedWeight
        protected set

    val effectiveWeight: Double
        get() = seedWeight + learnedWeight

    /**
     * 학습 변화량을 유효 가중치 상·하한 안에서 적용하고 실제 적용된 변화량을 돌려준다.
     * 수동 설정값이 이미 범위 밖이면 그 값을 경계로 본다 (학습이 수동 값을 깎지 않도록).
     */
    fun applyLearned(delta: Double, min: Double, max: Double): Double {
        val before = effectiveWeight
        val after = (before + delta).coerceIn(minOf(min, before), maxOf(max, before))
        learnedWeight = after - seedWeight
        return after - before
    }

    fun changeSeedWeight(seedWeight: Double) {
        this.seedWeight = seedWeight
    }

    fun resetLearned() {
        learnedWeight = 0.0
    }
}

/** V43 hotdeal_feed_item — 웹 추천 결과 */
@Entity
@Table(name = "hotdeal_feed_item")
class HotdealFeedItemEntity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hotdeal_item_id", nullable = false, unique = true)
    val item: HotdealItemEntity,
    score: Double,
    matchedTerms: JsonNode,
    popular: Boolean,
    val scoringVersion: String,
    val recommendedAt: Instant,
    evaluatedAt: Instant,
    val expiresAt: Instant?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var score: Double = score
        protected set

    /** 추천 이유·매칭 항목·당시 가중치·인기 보너스 */
    @JdbcTypeCode(SqlTypes.JSON)
    var matchedTerms: JsonNode = matchedTerms
        protected set

    @Column(nullable = false)
    var popular: Boolean = popular
        protected set

    var evaluatedAt: Instant = evaluatedAt
        protected set

    /** V44 — 최근 재평가에서 추천 조건을 충족했는지. false 면 기본 피드에서 숨긴다 */
    var active: Boolean = true
        protected set

    // 재평가 시 점수와 근거만 갱신한다. 최초 추천 시각은 유지한다.
    fun updateEvaluation(score: Double, matchedTerms: JsonNode, popular: Boolean, evaluatedAt: Instant) {
        this.score = score
        this.matchedTerms = matchedTerms
        this.popular = popular
        this.evaluatedAt = evaluatedAt
        this.active = true
    }

    /** V46 — LLM 관련성·제외 판정 (relevant, excluded, reason, status, model, input_hash, judged_at) */
    @JdbcTypeCode(SqlTypes.JSON)
    var judgment: JsonNode? = null
        protected set

    /** V46 — 관련 없음 또는 제외 판정이면 true. 기본 피드에서 숨긴다 */
    var judgedOut: Boolean = false
        protected set

    /** 추천 조건을 벗어나면 삭제하지 않고 숨긴다 (8.5) */
    fun deactivate(evaluatedAt: Instant) {
        this.active = false
        this.evaluatedAt = evaluatedAt
    }

    fun applyJudgment(judgment: JsonNode, judgedOut: Boolean) {
        this.judgment = judgment
        this.judgedOut = judgedOut
    }
}

/** V44 hotdeal_feedback — 항목별 현재 피드백. 취소하면 삭제한다 */
@Entity
@Table(name = "hotdeal_feedback")
class HotdealFeedbackEntity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hotdeal_feed_item_id", nullable = false, unique = true)
    val feedItem: HotdealFeedItemEntity,
    kind: FeedbackKindEnum,
    @Enumerated(EnumType.STRING)
    val channel: FeedbackChannelEnum,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var kind: FeedbackKindEnum = kind
        protected set

    fun change(kind: FeedbackKindEnum) {
        this.kind = kind
    }
}

/** V44 hotdeal_feedback_log — 피드백 변경과 실제 적용된 학습 변화량 */
@Entity
@Table(name = "hotdeal_feedback_log")
class HotdealFeedbackLogEntity(
    val feedbackEventId: UUID,
    @Column(name = "hotdeal_feed_item_id")
    val feedItemId: Long,
    val profileId: Long?,
    @Enumerated(EnumType.STRING)
    val previousKind: FeedbackKindEnum?,
    @Enumerated(EnumType.STRING)
    val newKind: FeedbackKindEnum?,
    val delta: Double,
    val previousWeight: Double?,
    val newWeight: Double?,
    val reason: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
