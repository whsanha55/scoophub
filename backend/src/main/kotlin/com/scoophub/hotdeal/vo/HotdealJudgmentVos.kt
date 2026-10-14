package com.scoophub.hotdeal.vo

/** 판정 대상 추천 항목. inputHash 가 저장된 판정의 해시와 같으면 다시 판정하지 않는다 */
data class JudgmentCandidate(
    val feedItemId: Long,
    val title: String,
    val source: String,
    val popular: Boolean,
    val matchedKeywords: List<String>,
    val inputHash: String,
)

/** status: llm(모델 판정) | rule(규칙 판정, 재시도 안 함) | fallback(LLM 실패 대체, 다음 주기에 재판정) */
data class HotdealJudgment(
    val relevant: Boolean,
    val excluded: Boolean,
    val reason: String,
    val status: String,
    val inputHash: String,
) {
    val judgedOut: Boolean
        get() = !relevant || excluded

    companion object {
        const val STATUS_LLM = "llm"
        const val STATUS_RULE = "rule"
        const val STATUS_FALLBACK = "fallback"
    }
}
