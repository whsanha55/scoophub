package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealFeedItemRepository
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.vo.HotdealJudgment
import com.scoophub.hotdeal.vo.JudgmentCandidate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock

/** 판정 대상 선정과 결과 저장. LLM 호출은 트랜잭션 밖(HotdealJudgmentFacade)에서 한다 */
@Service
class HotdealJudgmentService(
    private val feedItemRepository: HotdealFeedItemRepository,
    private val profileRepository: HotdealInterestProfileRepository,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    data class Pending(val candidates: List<JudgmentCandidate>, val excludeKeywords: List<String>)

    /** 판정이 없거나, 입력이 바뀌었거나, 지난번에 LLM 실패로 대체 판정한 항목. 최신 추천부터 limit 건 */
    fun findPending(limit: Int): Pending {
        val excludeKeywords = profileRepository.findAll()
            .filter { it.kind == ProfileKindEnum.EXCLUDE }
            .map { it.value }
            .sorted()
        val candidates = feedItemRepository.findActiveWithItem(clock.instant())
            .sortedByDescending { it.recommendedAt }
            .mapNotNull { feedItem ->
                val termNodes: Iterable<JsonNode> = feedItem.matchedTerms.path("terms")
                val matched = termNodes.map { it.path("value").asText() }.sorted()
                val hash = inputHash(feedItem.item.title, feedItem.popular, matched, excludeKeywords)
                val judgment = feedItem.judgment
                val upToDate = judgment != null &&
                    judgment.path("input_hash").asText() == hash &&
                    judgment.path("status").asText() != HotdealJudgment.STATUS_FALLBACK
                if (upToDate) {
                    null
                } else {
                    JudgmentCandidate(
                        feedItemId = requireNotNull(feedItem.id),
                        title = feedItem.item.title,
                        source = feedItem.item.source.name,
                        popular = feedItem.popular,
                        matchedKeywords = matched,
                        inputHash = hash,
                    )
                }
            }
            .take(limit)
        return Pending(candidates, excludeKeywords)
    }

    @Transactional
    fun saveJudgments(judgments: Map<Long, HotdealJudgment>) {
        val judgedAt = clock.instant()
        feedItemRepository.findAllById(judgments.keys).forEach { feedItem ->
            val judgment = judgments.getValue(requireNotNull(feedItem.id))
            feedItem.applyJudgment(
                jsonMapper.valueToTree(
                    mapOf(
                        "relevant" to judgment.relevant,
                        "excluded" to judgment.excluded,
                        "reason" to judgment.reason,
                        "status" to judgment.status,
                        "input_hash" to judgment.inputHash,
                        "judged_at" to judgedAt.toString(),
                    ),
                ),
                judgedOut = judgment.judgedOut,
            )
        }
    }

    private fun inputHash(title: String, popular: Boolean, matched: List<String>, excludes: List<String>): String {
        val input = listOf(title, popular.toString(), matched.joinToString("\u0001"), excludes.joinToString("\u0001"))
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
