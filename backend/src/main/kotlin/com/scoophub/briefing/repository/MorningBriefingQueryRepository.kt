package com.scoophub.briefing.repository

import com.scoophub.briefing.enums.BriefingSectionEnum
import com.scoophub.briefing.vo.BriefingCandidateRow
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * 아침 브리핑 후보 조회. 기준 비교와 중복 제외는 [com.scoophub.briefing.MorningBriefing] 이 맡고,
 * 여기서는 기간 안의 후보만 가져온다.
 */
@Repository
class MorningBriefingQueryRepository(private val jdbcClient: JdbcClient) {

    /**
     * 기간 안에 발행되고 중요도가 매겨진 뉴스. 실시간 발송 여부와 무관하되,
     * 이미 다른 기사와 같은 사건으로 판정된 기사(duplicate)는 뺀다. 최신순.
     */
    fun findNewsCandidates(since: Instant): List<BriefingCandidateRow> = jdbcClient.sql(
        """
        SELECT id, COALESCE(NULLIF(summary_ko, ''), headline) AS title, url, importance
        FROM news_article
        WHERE published_at >= :since
            AND importance IS NOT NULL
            AND decision_reason IS DISTINCT FROM 'duplicate'
        ORDER BY published_at DESC, id DESC
        """.trimIndent(),
    )
        .param("since", Timestamp.from(since))
        .query { rs, _ ->
            BriefingCandidateRow(
                section = BriefingSectionEnum.NEWS,
                key = rs.getLong("id").toString(),
                title = rs.getString("title"),
                url = rs.getString("url"),
                metric = rs.getLong("importance"),
            )
        }
        .list()

    /** 기간 안의 수집에서 본 HN 스토리. 링크가 없는 글(Ask HN 등)은 HN 토론 페이지로 잇는다 */
    fun findHackerNewsCandidates(since: Instant): List<BriefingCandidateRow> = findCrawled(
        sql = """
            SELECT key, response ->> 'title' AS title, response ->> 'url' AS url,
                (response ->> 'score')::bigint AS metric, NULL AS description
            FROM crawl_data
            WHERE category = 'community'
                AND purpose = 'hackernews'
                AND (response ->> 'fetched_at')::timestamptz >= :since
                AND response ->> 'item_type' = 'story'
        """.trimIndent(),
        since = since,
        section = BriefingSectionEnum.HACKER_NEWS,
    ).map { it.copy(url = it.url ?: "$HN_ITEM_URL${it.key}") }

    /** 기간 안의 수집에서 본 하루(daily) 트렌딩 저장소. metric 은 그날 늘어난 스타 수 */
    fun findGithubCandidates(since: Instant): List<BriefingCandidateRow> = findCrawled(
        sql = """
            SELECT key, response ->> 'fullname' AS title, response ->> 'url' AS url,
                (response ->> 'current_period_stars')::bigint AS metric, response ->> 'description' AS description
            FROM crawl_data
            WHERE category = 'community'
                AND purpose = 'github'
                AND (response ->> 'fetched_at')::timestamptz >= :since
                AND response ->> 'period' = 'daily'
        """.trimIndent(),
        since = since,
        section = BriefingSectionEnum.GITHUB,
    )

    /** 기간 안의 수집에서 본 Dev.to 글 */
    fun findDevtoCandidates(since: Instant): List<BriefingCandidateRow> = findCrawled(
        sql = """
            SELECT key, response ->> 'title' AS title, response ->> 'url' AS url,
                (response ->> 'reactions_count')::bigint AS metric, NULL AS description
            FROM crawl_data
            WHERE category = 'feed'
                AND purpose = 'devblog'
                AND (response ->> 'fetched_at')::timestamptz >= :since
        """.trimIndent(),
        since = since,
        section = BriefingSectionEnum.DEVTO,
    )

    private fun findCrawled(sql: String, since: Instant, section: BriefingSectionEnum): List<BriefingCandidateRow> =
        jdbcClient.sql(sql)
            .param("since", Timestamp.from(since))
            .query { rs, _ ->
                BriefingCandidateRow(
                    section = section,
                    key = rs.getString("key"),
                    title = rs.getString("title").orEmpty(),
                    url = rs.getString("url")?.ifBlank { null },
                    metric = rs.getLong("metric"),
                    description = rs.getString("description"),
                )
            }
            .list()

    companion object {
        private const val HN_ITEM_URL = "https://news.ycombinator.com/item?id="
    }
}
