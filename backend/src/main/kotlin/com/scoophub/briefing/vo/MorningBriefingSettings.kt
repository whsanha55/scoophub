package com.scoophub.briefing.vo

import com.scoophub.briefing.enums.BriefingSectionEnum
import tools.jackson.databind.JsonNode
import java.time.Duration

/** crawl_config('morning_briefing').params. 값이 없으면 기본값을 쓴다 */
data class MorningBriefingSettings(
    val lookback: Duration,
    val maxItems: Int,
    val newsMinImportance: Int,
    val hnMinScore: Int,
    val githubMinStars: Int,
    val devtoMinReactions: Int,
) {
    fun thresholdOf(section: BriefingSectionEnum): Long = when (section) {
        BriefingSectionEnum.NEWS -> newsMinImportance
        BriefingSectionEnum.HACKER_NEWS -> hnMinScore
        BriefingSectionEnum.GITHUB -> githubMinStars
        BriefingSectionEnum.DEVTO -> devtoMinReactions
    }.toLong()

    companion object {
        fun from(params: JsonNode) = MorningBriefingSettings(
            lookback = Duration.ofHours(params.path("lookback_hours").asLong(24)),
            maxItems = params.path("max_items").asInt(5),
            newsMinImportance = params.path("news_min_importance").asInt(5),
            hnMinScore = params.path("hn_min_score").asInt(300),
            githubMinStars = params.path("github_min_stars").asInt(300),
            devtoMinReactions = params.path("devto_min_reactions").asInt(50),
        )
    }
}
