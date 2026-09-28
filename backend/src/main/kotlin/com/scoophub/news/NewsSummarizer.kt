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

private val HANGUL = Regex("[가-힣]")

/** legacy `news/summarizer.py` — 미완료 기사 LLM 요약 (청크 단위) */
@Component
class NewsSummarizer(
    private val llm: LlmClient,
    private val jdbcClient: JdbcClient,
    private val jsonMapper: JsonMapper,
) {
    data class ArticleRow(
        val id: Int,
        val title: String,
        val summary: String?,
        val importance: Int,
        val category: String?,
    )

    data class Result(val success: Int, val failed: Int, val error: Int) {
        val total: Int get() = success + failed + error
    }

    /** summary_status != success 인 기사(최근 24h)를 청크로 요약. 결과 카운트 반환 */
    fun summarizeIncomplete(): Result {
        val rows = jdbcClient.sql(
            """
            SELECT id, title, summary, importance, category FROM feed_news
            WHERE summary_status <> 'success' AND duplicated = false
              AND created_at >= now() - interval '$RETRY_WINDOW_HOURS hours'
            ORDER BY id
            """.trimIndent(),
        )
            .query { rs, _ ->
                ArticleRow(
                    rs.getInt("id"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getInt("importance"),
                    rs.getString("category"),
                )
            }
            .list()
        if (rows.isEmpty()) {
            log.info { "No incomplete articles to summarize" }
            return Result(0, 0, 0)
        }

        var success = 0
        var failed = 0
        var error = 0
        for (start in rows.indices step CHUNK_SIZE) {
            val c = processChunk(rows.subList(start, minOf(start + CHUNK_SIZE, rows.size)))
            success += c.success
            failed += c.failed
            error += c.error
        }
        log.info { "Summarized ${rows.size} articles: $success success, $failed failed, $error error" }
        return Result(success, failed, error)
    }

    private data class Update(
        val id: Int,
        val title: String,
        val summary: String?,
        val importance: Int,
        val category: String?,
        val status: String,
    )

    private fun processChunk(chunk: List<ArticleRow>): Result {
        // 제목 번역 필요 여부(한국어 아님) 표시
        val needsTitle = chunk.associate { it.id to !HANGUL.containsMatchIn(it.title) }
        val articlesText = chunk.withIndex().joinToString("") { (i, row) ->
            val tail = if (needsTitle[row.id] == true) "  (제목 한국어 번역 필요)" else ""
            "[idx ${i + 1}]$tail\n제목: ${row.title}\n본문: ${row.summary.orEmpty()}\n\n"
        }
        val userPrompt = "다음 ${chunk.size}개 기사를 처리하세요:\n\n$articlesText"

        val parsed: Map<Int, JsonNode> = try {
            parse(llm.chat(SYSTEM_PROMPT, userPrompt))
        } catch (e: Exception) {
            log.error { "LLM summarization failed for chunk (${chunk.size} articles): ${e.message}" }
            markError(chunk.map { it.id })
            return Result(0, 0, chunk.size)
        }

        val updates = mutableListOf<Update>()
        var success = 0
        var failed = 0
        chunk.forEachIndexed { idx, row ->
            val item = parsed[idx + 1]
            val summaryKo = item?.get("summary_ko")?.takeIf { !it.isNull }?.asText()
            if (item != null && !summaryKo.isNullOrBlank()) {
                val title = if (needsTitle[row.id] == true) {
                    item["title_ko"]?.takeIf { !it.isNull }?.asText()?.trim()?.ifEmpty { null } ?: row.title
                } else {
                    row.title
                }
                updates +=
                    Update(
                        row.id,
                        title,
                        summaryKo.trim(),
                        clampImportance(item["importance"], row.importance),
                        cleanCategory(item["category"]),
                        "success",
                    )
                success++
            } else {
                // 누락/불완전 항목: 기존 필드 유지, failed
                updates += Update(row.id, row.title, row.summary, row.importance, row.category, "failed")
                failed++
            }
        }

        // unnest 일괄 갱신
        val params = MapSqlParameterSource()
            .addValue("ids", updates.map { it.id }.toIntArray())
            .addValue("titles", updates.map { it.title }.toTypedArray())
            .addValue("summaries", updates.map { it.summary }.toTypedArray())
            .addValue("importances", updates.map { it.importance }.toIntArray())
            .addValue("categories", updates.map { it.category }.toTypedArray())
            .addValue("statuses", updates.map { it.status }.toTypedArray())
        jdbcClient.sql(
            """
            UPDATE feed_news AS n SET
              title = v.title, summary = v.summary, importance = v.importance,
              category = v.category, summary_status = v.status, updated_at = now()
            FROM (SELECT * FROM unnest(:ids::int[], :titles::text[], :summaries::text[],
                  :importances::smallint[], :categories::text[], :statuses::text[])
                  AS t(id, title, summary, importance, category, status)) AS v
            WHERE n.id = v.id
            """.trimIndent(),
        )
            .paramSource(params)
            .update()
        return Result(success, failed, 0)
    }

    private fun markError(ids: List<Int>) {
        jdbcClient.sql("UPDATE feed_news SET summary_status = 'error', updated_at = now() WHERE id IN (:ids)")
            .param("ids", ids)
            .update()
    }

    /** LLM JSON 배열 응답 → {idx: item} */
    fun parse(response: String): Map<Int, JsonNode> {
        var text = response.trim()
        if (text.startsWith("```")) {
            text = text.replace(Regex("^```[a-zA-Z]*\\n?|\\n?```$"), "").trim()
        }
        val lo = text.indexOf('[')
        val hi = text.lastIndexOf(']')
        if (lo == -1 || hi == -1 || hi < lo) {
            throw IllegalStateException("no JSON array in LLM response")
        }
        val data: JsonNode = jsonMapper.readTree(text.substring(lo, hi + 1))
        val result = mutableMapOf<Int, JsonNode>()
        for (item in data.toList()) {
            val idx = item["idx"]?.takeIf { !it.isNull }?.asInt() ?: continue
            result[idx] = item
        }
        return result
    }

    companion object {
        private const val CHUNK_SIZE = 20
        private const val RETRY_WINDOW_HOURS = 24

        private val CATEGORIES = setOf(
            "politics", "economy", "markets", "tech", "society",
            "world", "disaster", "science", "culture", "other",
        )

        private val SYSTEM_PROMPT = """
            당신은 한국어 뉴스 요약 도우미입니다.
            입력으로 여러 개의 뉴스 기사가 주어집니다. 각 기사는 idx, 제목, 본문을 가집니다.

            각 기사마다 다음을 생성하세요:
            - summary_ko: 한국어로 3-5문장 요약
            - importance: 중요도 정수 1~5 (5=속보·매우중요, 1=단순·일상)
            - category: 다음 중 하나만 선택 (애매할 때만 other):
                politics : 정치·선거·정부·외교부
                economy  : 거시경제·정책·금리·고용·무역·환율
                markets  : 증시·기업실적·M&A·코인·원자재
                tech     : IT·AI·반도체·플랫폼
                society  : 사건·사고·범죄·노동·교육·복지 (재난 제외)
                world    : 국제·분쟁·해외정세
                disaster : 지진·태풍·홍수·화재·테러 등 재난
                science  : 과학·보건의료·환경기후
                culture  : 문화·예술·스포츠·연예·라이프
                other    : 위 어디에도 안 맞을 때만
            - title_ko: 제목이 한국어가 아닌 경우에만 한국어 번역 제목. 이미 한국어면 생략

            반드시 아래 형식의 JSON 배열만 출력하세요. 설명·코드블록 없이 JSON만:
            [{"idx": 1, "summary_ko": "...", "importance": 3, "category": "markets", "title_ko": "..."}, ...]
        """.trimIndent()

        fun clampImportance(value: JsonNode?, fallback: Int): Int {
            val n = value?.takeIf { !it.isNull }?.asInt(-1) ?: -1
            return if (n < 0) fallback else n.coerceIn(1, 5)
        }

        fun cleanCategory(value: JsonNode?): String {
            val c = value?.takeIf { !it.isNull }?.asText()?.trim()?.lowercase().orEmpty()
            return if (c in CATEGORIES) c else "other"
        }
    }
}
