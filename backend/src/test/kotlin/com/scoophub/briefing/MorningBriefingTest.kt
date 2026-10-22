package com.scoophub.briefing

import com.scoophub.briefing.enums.BriefingSectionEnum
import com.scoophub.briefing.vo.BriefingCandidateRow
import com.scoophub.briefing.vo.MorningBriefingSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate

class MorningBriefingTest {
    private val mapper = JsonMapper.builder().build()
    private val settings = MorningBriefingSettings.from(mapper.readTree("{}"))
    private val date = LocalDate.of(2026, 10, 23)

    private fun news(key: String, title: String = "뉴스 $key", importance: Long = 5) =
        BriefingCandidateRow(BriefingSectionEnum.NEWS, key, title, "https://news.test/$key", importance)

    private fun hn(key: String, score: Long, title: String = "HN $key") =
        BriefingCandidateRow(BriefingSectionEnum.HACKER_NEWS, key, title, "https://hn.test/$key", score)

    private fun github(key: String, stars: Long, description: String? = null) = BriefingCandidateRow(
        BriefingSectionEnum.GITHUB,
        "https://github.com/$key",
        key,
        "https://github.com/$key",
        stars,
        description,
    )

    private fun devto(key: String, reactions: Long) =
        BriefingCandidateRow(BriefingSectionEnum.DEVTO, key, "Dev $key", "https://dev.test/$key", reactions)

    private fun compose(candidates: List<BriefingCandidateRow>, briefedKeys: Set<String> = emptySet()) =
        MorningBriefing.compose(date, candidates, briefedKeys, settings)

    @Test
    fun `묶음마다 기준을 넘은 항목만 넣는다`() {
        // given
        val candidates = listOf(
            news("1", importance = 5),
            news("2", importance = 4),
            hn("10", score = 300),
            hn("11", score = 299),
            github("a/kept", stars = 300),
            github("a/dropped", stars = 299),
            devto("20", reactions = 50),
            devto("21", reactions = 49),
        )

        // when
        val briefing = compose(candidates)

        // then
        assertThat(briefing?.briefingKeys)
            .containsExactly("news:1", "hn:10", "github:https://github.com/a/kept", "devto:20")
    }

    @Test
    fun `묶음마다 높은 순으로 최대 5건만 넣는다`() {
        // given
        val candidates = listOf(310L, 900L, 450L, 300L, 700L, 1200L, 320L).mapIndexed { i, score -> hn("$i", score) }

        // when
        val briefing = compose(candidates)

        // then — 1200, 900, 700, 450, 320
        assertThat(briefing?.briefingKeys).containsExactly("hn:5", "hn:1", "hn:4", "hn:2", "hn:6")
    }

    @Test
    fun `점수가 같으면 받은 순서를 지킨다`() {
        // given — 뉴스는 모두 5점이고 최신순으로 들어온다
        val candidates = (1..7).map { news("$it") }

        // when
        val briefing = compose(candidates)

        // then
        assertThat(briefing?.briefingKeys).containsExactly("news:1", "news:2", "news:3", "news:4", "news:5")
    }

    @Test
    fun `이미 브리핑에 나온 항목은 다시 넣지 않고 다음 순위로 채운다`() {
        // given
        val candidates = (1..6).map { github("o/r$it", stars = 1000L - it) }

        // when
        val briefing = compose(candidates, briefedKeys = setOf("github:https://github.com/o/r1"))

        // then
        assertThat(briefing?.briefingKeys).containsExactly(
            "github:https://github.com/o/r2",
            "github:https://github.com/o/r3",
            "github:https://github.com/o/r4",
            "github:https://github.com/o/r5",
            "github:https://github.com/o/r6",
        )
    }

    @Test
    fun `기준을 넘은 게 없는 묶음은 뺀다`() {
        // when
        val briefing = compose(listOf(hn("1", score = 500), devto("2", reactions = 10)))

        // then
        assertThat(briefing?.sections?.keys).containsExactly(BriefingSectionEnum.HACKER_NEWS)
    }

    @Test
    fun `네 묶음이 모두 비면 브리핑을 만들지 않는다`() {
        // when
        val briefing = compose(
            listOf(news("1", importance = 3), hn("2", score = 10), github("o/r", stars = 1)),
            briefedKeys = emptySet(),
        )

        // then
        assertThat(briefing).isNull()
    }

    @Test
    fun `이미 나온 항목만 남으면 브리핑을 만들지 않는다`() {
        // when
        val briefing = compose(listOf(hn("1", score = 500)), briefedKeys = setOf("hn:1"))

        // then
        assertThat(briefing).isNull()
    }

    @Test
    fun `정해진 틀로 메시지를 만든다`() {
        // given
        val candidates = listOf(
            news("1", title = "엔비디아, 차세대 칩 출하 앞당겨"),
            hn("2", score = 812, title = "Show HN: SQLite extension for local LLMs"),
            github("owner/repo", stars = 1240, description = "설명 한 줄"),
            github("owner/bare", stars = 400),
            devto("3", reactions = 87),
        )

        // when
        val blocks = compose(candidates)?.blocks()

        // then
        assertThat(blocks).containsExactly(
            "<b>☀️ 아침 브리핑 10/23</b>",
            "📰 뉴스\n · <a href=\"https://news.test/1\">엔비디아, 차세대 칩 출하 앞당겨</a>",
            "🔥 HN\n · <a href=\"https://hn.test/2\">Show HN: SQLite extension for local LLMs</a> (812점)",
            "⭐ GitHub\n" +
                " · <a href=\"https://github.com/owner/repo\">owner/repo</a> — 설명 한 줄 (+1,240★)\n" +
                " · <a href=\"https://github.com/owner/bare\">owner/bare</a> (+400★)",
            "📝 Dev.to\n · <a href=\"https://dev.test/3\">Dev 3</a> (반응 87)",
        )
    }

    @Test
    fun `제목의 HTML 특수문자는 이스케이프하고 긴 제목은 자른다`() {
        // given
        val longTitle = "가".repeat(200)
        val candidates = listOf(hn("1", score = 500, title = "<script> & co"), hn("2", score = 400, title = longTitle))

        // when
        val section = compose(candidates)?.blocks()?.get(1)

        // then
        assertThat(section).contains("&lt;script&gt; &amp; co")
        assertThat(section).contains("가".repeat(150) + "…")
        assertThat(section).doesNotContain("가".repeat(151))
    }

    @Test
    fun `주소가 없으면 링크 없이 제목만 쓴다`() {
        // given
        val candidate = BriefingCandidateRow(BriefingSectionEnum.NEWS, "1", "주소 없는 뉴스", null, 5)

        // when
        val blocks = compose(listOf(candidate))?.blocks()

        // then
        assertThat(blocks?.get(1)).isEqualTo("📰 뉴스\n · 주소 없는 뉴스")
    }

    @Test
    fun `설정값이 없으면 기본 기준을 쓴다`() {
        // then
        assertThat(settings.lookback).isEqualTo(Duration.ofHours(24))
        assertThat(settings.maxItems).isEqualTo(5)
        assertThat(settings.thresholdOf(BriefingSectionEnum.NEWS)).isEqualTo(5)
        assertThat(settings.thresholdOf(BriefingSectionEnum.HACKER_NEWS)).isEqualTo(300)
        assertThat(settings.thresholdOf(BriefingSectionEnum.GITHUB)).isEqualTo(300)
        assertThat(settings.thresholdOf(BriefingSectionEnum.DEVTO)).isEqualTo(50)
    }

    @Test
    fun `설정으로 기준과 최대 건수를 바꾼다`() {
        // given
        val custom = MorningBriefingSettings.from(
            mapper.readTree(
                """
                {"lookback_hours": 36, "max_items": 2, "news_min_importance": 4, "hn_min_score": 100,
                 "github_min_stars": 50, "devto_min_reactions": 10}
                """.trimIndent(),
            ),
        )
        val candidates =
            listOf(news("1", importance = 4), hn("2", score = 150), hn("3", score = 120), hn("4", score = 110))

        // when
        val briefing = MorningBriefing.compose(date, candidates, emptySet(), custom)

        // then
        assertThat(custom.lookback).isEqualTo(Duration.ofHours(36))
        assertThat(briefing?.briefingKeys).containsExactly("news:1", "hn:2", "hn:3")
    }
}
