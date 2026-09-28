package com.scoophub.news

import com.scoophub.external.llm.LlmClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

private val log = KotlinLogging.logger {}

/** legacy `news/dedup.py` 의 llm_dedup — LLM 으로 중복 그룹 판단 */
@Component
class NewsDedup(private val llm: LlmClient, private val jdbcClient: JdbcClient, private val jsonMapper: JsonMapper) {
    data class ArticleRef(val id: Int, val title: String, val source: String, val url: String, val summary: String?)

    /** 중복 처리된 기사 수 반환. 실패 시 전체 duplicated=false 유지 */
    fun llmDedup(newArticleIds: List<Int>, dedupWindowHours: Int = 24): Int {
        if (newArticleIds.isEmpty()) {
            return 0
        }

        val newRows = queryArticles("id IN (:ids)", mapOf("ids" to newArticleIds))
        if (newRows.isEmpty()) {
            return 0
        }
        val existingRows = queryArticles(
            "duplicated = false AND created_at >= :since AND id NOT IN (:ids)",
            mapOf(
                "ids" to newArticleIds,
                "since" to Timestamp.from(Instant.now().minusSeconds(dedupWindowHours * 3600L)),
            ),
        )

        // 프롬프트 구성
        val lines = mutableListOf<String>()
        if (existingRows.isNotEmpty()) {
            lines += "[기존 기사]"
            existingRows.forEachIndexed { i, r ->
                lines +=
                    "E${i + 1}: 제목=${r.title} | 매체=${r.source} | URL=${r.url} | 요약=${r.summary.orEmpty().take(100)}"
            }
            lines += ""
        }
        lines += "[신규 기사]"
        newRows.forEachIndexed { i, r ->
            lines += "N${i + 1}: 제목=${r.title} | 매체=${r.source} | URL=${r.url} | 요약=${r.summary.orEmpty().take(100)}"
        }

        val idxToId = buildMap {
            existingRows.forEachIndexed { i, r -> put("E${i + 1}", r.id) }
            newRows.forEachIndexed { i, r -> put("N${i + 1}", r.id) }
        }

        val groups = try {
            parseGroups(llm.chat(DEDUP_SYSTEM_PROMPT, lines.joinToString("\n")))
        } catch (e: Exception) {
            log.error { "LLM dedup failed, keeping all as non-duplicate: ${e.message}" }
            return 0
        }
        if (groups.isEmpty()) {
            log.info { "LLM dedup: no duplicates found among ${newRows.size} new articles" }
            return 0
        }

        var totalDeduped = 0
        for (group in groups) {
            val idsInGroup = group.mapNotNull { idxToId[it] }
            if (idsInGroup.size < 2) {
                continue
            }
            // 대표 기사: 기존 기사 우선, 없으면 그룹 첫 건
            val representative = group.firstOrNull { it.startsWith("E") }?.let { idxToId[it] } ?: idsInGroup.first()
            val duplicateIds = idsInGroup.filter { it != representative }
            if (duplicateIds.isNotEmpty()) {
                jdbcClient.sql(
                    "UPDATE feed_news SET duplicated = true, duplicated_news_id = :rep, updated_at = now() " +
                        "WHERE id IN (:ids)",
                )
                    .param("rep", representative)
                    .param("ids", duplicateIds)
                    .update()
                totalDeduped += duplicateIds.size
            }
        }
        log.info { "LLM dedup: $totalDeduped duplicates marked among ${newRows.size} new articles" }
        return totalDeduped
    }

    private fun queryArticles(where: String, params: Map<String, Any>): List<ArticleRef> =
        jdbcClient.sql("SELECT id, title, source, url, summary FROM feed_news WHERE $where")
            .paramSource(MapSqlParameterSource(params))
            .query { rs, _ ->
                ArticleRef(
                    rs.getInt("id"),
                    rs.getString("title"),
                    rs.getString("source"),
                    rs.getString("url"),
                    rs.getString("summary"),
                )
            }
            .list()

    /** LLM JSON 응답에서 groups 파싱 (2건 이상 그룹만) */
    fun parseGroups(response: String): List<List<String>> {
        var text = response.trim()
        if (text.startsWith("```")) {
            text = text.replace(Regex("^```[a-zA-Z]*\\n?|\\n?```$"), "").trim()
        }
        val lo = text.indexOf('{')
        val hi = text.lastIndexOf('}')
        if (lo == -1 || hi == -1 || hi < lo) {
            throw IllegalStateException("no JSON object in LLM dedup response")
        }
        val data: JsonNode = jsonMapper.readTree(text.substring(lo, hi + 1))
        val groups = data["groups"]?.takeIf { it.isArray } ?: return emptyList()
        return groups.toList()
            .filter { it.isArray && it.size() >= 2 }
            .map { g -> g.toList().map { it.asText() } }
    }

    companion object {
        private val DEDUP_SYSTEM_PROMPT = """
            당신은 한국어 뉴스 중복 판단 전문가입니다.

            입력으로 기존 기사와 신규 기사 목록이 주어집니다. 중복 그룹을 찾아내세요.

            ## 중복 기준 (같은 기사)
            - 통신사 기사를 여러 매체가 재배포한 경우
            - 제목/본문이 약간만 다르고 본질적으로 동일한 내용인 경우
            - 같은 기사의 업데이트판

            ## 중복 아님 기준 (다른 기사)
            - 같은 사건을 다른 각도/취재로 보도한 경우
            - 같은 토픽의 후속 보도
            - 다른 매체의 독자 취재 기사

            반드시 아래 형식의 JSON만 출력하세요. 설명·코드블록 없이 JSON만:
            {"groups": [[idx1, idx2, ...], [idx3, idx4, ...]]}

            - 각 그룹은 서로 중복인 기사의 idx 목록입니다.
            - 중복 그룹이 없으면 빈 배열을 출력하세요: {"groups": []}
            - 한 그룹에 기존 기사(E로 시작)와 신규 기사(N으로 시작)가 섞여 있을 수 있습니다.
        """.trimIndent()
    }
}
