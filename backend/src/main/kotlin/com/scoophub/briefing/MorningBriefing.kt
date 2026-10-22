package com.scoophub.briefing

import com.scoophub.briefing.enums.BriefingSectionEnum
import com.scoophub.briefing.vo.BriefingCandidateRow
import com.scoophub.briefing.vo.MorningBriefingSettings
import com.scoophub.global.notify.NotifyCard.Companion.escapeHtml
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 아침 브리핑 한 회차. 비어 있는 묶음은 들어 있지 않다.
 * 문장은 LLM 없이 정해진 틀에 채운다.
 */
data class MorningBriefing(val date: LocalDate, val sections: Map<BriefingSectionEnum, List<BriefingCandidateRow>>) {

    val briefingKeys: List<String>
        get() = sections.values.flatten().map { it.briefingKey }

    /** 머리글과 묶음별 블록 (Telegram HTML). 블록을 빈 줄로 이으면 한 장의 메시지가 된다 */
    fun blocks(): List<String> = listOf("<b>☀️ 아침 브리핑 ${date.format(DATE)}</b>") +
        sections.map { (section, items) -> section.title + items.joinToString("") { "\n · ${line(it)}" } }

    private fun line(item: BriefingCandidateRow): String {
        val title = escapeHtml(truncate(item.title, TITLE_MAX))
        val head = item.url?.let { """<a href="${escapeHtml(it)}">$title</a>""" } ?: title
        return when (item.section) {
            BriefingSectionEnum.NEWS -> head

            BriefingSectionEnum.HACKER_NEWS -> "$head (${count(item.metric)}점)"

            BriefingSectionEnum.GITHUB -> {
                val description = item.description?.takeIf { it.isNotBlank() }
                    ?.let { " — ${escapeHtml(truncate(it.trim(), DESCRIPTION_MAX))}" }
                    .orEmpty()
                "$head$description (+${count(item.metric)}★)"
            }

            BriefingSectionEnum.DEVTO -> "$head (반응 ${count(item.metric)})"
        }
    }

    companion object {
        private const val TITLE_MAX = 150
        private const val DESCRIPTION_MAX = 100
        private val DATE = DateTimeFormatter.ofPattern("MM/dd")

        /**
         * 묶음마다 기준 이상이면서 이전 브리핑에 나오지 않은 항목을 높은 순으로 최대 maxItems 건 고른다.
         * 같은 값이면 candidates 순서를 지킨다. 모든 묶음이 비면 null (그날은 보내지 않는다).
         */
        fun compose(
            date: LocalDate,
            candidates: List<BriefingCandidateRow>,
            briefedKeys: Set<String>,
            settings: MorningBriefingSettings,
        ): MorningBriefing? {
            val sections = BriefingSectionEnum.entries
                .associateWith { section ->
                    candidates
                        .filter { it.section == section }
                        .filter { it.metric >= settings.thresholdOf(section) && it.briefingKey !in briefedKeys }
                        .sortedByDescending { it.metric }
                        .take(settings.maxItems)
                }
                .filterValues { it.isNotEmpty() }
            return if (sections.isEmpty()) null else MorningBriefing(date, sections)
        }

        private fun truncate(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + "…"

        private fun count(value: Long): String = "%,d".format(Locale.ROOT, value)
    }
}
