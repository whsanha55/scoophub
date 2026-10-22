package com.scoophub.briefing.enums

/** 아침 브리핑의 묶음. 선언 순서가 메시지에 나오는 순서다. keyPrefix 는 이미 나온 항목 기록의 키 앞부분 */
enum class BriefingSectionEnum(val keyPrefix: String, val title: String) {
    NEWS("news", "📰 뉴스"),
    HACKER_NEWS("hn", "🔥 HN"),
    GITHUB("github", "⭐ GitHub"),
    DEVTO("devto", "📝 Dev.to"),
}
