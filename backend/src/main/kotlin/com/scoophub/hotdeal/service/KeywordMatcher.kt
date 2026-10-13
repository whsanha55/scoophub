package com.scoophub.hotdeal.service

/**
 * 관심 키워드 매칭 규칙 (docs/HOTDEAL_PLAN.md 8.1).
 * 영문·숫자 키워드는 부분 문자열 오탐(SSD → SSDS)을 막기 위해 토큰 경계로 매칭하고,
 * 한글 키워드는 부분 문자열 매칭을 쓴다. 한글 오탐(램 → 램프)은 관련성 판정(8.4)으로 거른다.
 */
object KeywordMatcher {

    private val LATIN_PATTERN = Regex("[A-Za-z]")

    fun matches(text: String, keyword: String): Boolean = when {
        keyword.isEmpty() -> false
        !LATIN_PATTERN.containsMatchIn(keyword) -> text.contains(keyword, ignoreCase = true)
        else -> tokenBoundaryPattern(keyword).containsMatchIn(text)
    }

    private fun tokenBoundaryPattern(keyword: String): Regex = Regex(
        "(?<![A-Za-z0-9])${Regex.escape(keyword)}(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
}
