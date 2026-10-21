package com.scoophub.hotdeal.facade

import com.scoophub.hotdeal.service.HotdealJudgmentService
import com.scoophub.hotdeal.service.HotdealLlmJudge
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/** 관련성·제외 판정 (8.4). 대상 선정 → LLM 판정(트랜잭션 밖) → 저장 */
@Component
class HotdealJudgmentFacade(private val judgmentService: HotdealJudgmentService, private val judge: HotdealLlmJudge) {

    /** 판정한 항목 수. 호출량을 제한하려고 한 번에 MAX_PER_RUN 건까지만 판정하고 나머지는 다음 주기로 넘긴다 */
    fun judgePending(): Int {
        val pending = judgmentService.findPending(MAX_PER_RUN)
        if (pending.candidates.isEmpty()) {
            return 0
        }
        val judgments = judge.judge(pending.candidates, pending.excludeKeywords)
        judgmentService.saveJudgments(judgments)
        log.info {
            "hotdeal judgment done - judged=${judgments.size}, " +
                "judgedOut=${judgments.values.count { it.judgedOut }}, " +
                "fallback=${judgments.values.count { it.status == "fallback" }}"
        }
        return judgments.size
    }

    companion object {
        const val MAX_PER_RUN = 40
    }
}
