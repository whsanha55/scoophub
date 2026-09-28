package com.scoophub.external.arxiv

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

data class ArxivPaper(
    val arxivId: String,
    val title: String,
    val authors: List<String>,
    val summary: String,
    val primaryCategory: String,
    val categories: List<String>,
    val pdfUrl: String?,
    val entryId: String,
    val published: Instant?,
    val updated: Instant?,
    val authorComment: String?,
    val journalRef: String?,
)

/** legacy `arxiv` 파이썬 라이브러리 대체 — Atom API 직접 호출 (Jsoup XML 파싱) */
@Component
class ArxivClient(restClientBuilder: RestClient.Builder) {
    private val restClient = restClientBuilder
        .baseUrl("https://export.arxiv.org/api/query")
        .build()

    /** cat:{category} 최신 제출순 조회 */
    fun searchByCategory(category: String, maxResults: Int): List<ArxivPaper> {
        val xml = restClient.get()
            .uri(
                "?search_query={query}&sortBy=submittedDate&sortOrder=descending&max_results={max}",
                "cat:$category",
                maxResults,
            )
            .retrieve()
            .body(String::class.java)
            ?: return emptyList()
        return parse(Jsoup.parse(xml, "", Parser.xmlParser()))
    }

    /** Atom XML → 논문 목록. 테스트를 위해 문서 주입 분리 */
    fun parse(doc: Document): List<ArxivPaper> = doc.select("entry").mapNotNull { entry ->
        val entryId = entry.selectFirst("id")?.text() ?: return@mapNotNull null
        // "http://arxiv.org/abs/2401.12345v1" → short id "2401.12345"
        val arxivId = entryId.substringAfterLast("/abs/").substringBefore('v')
        if (arxivId.isBlank()) {
            return@mapNotNull null
        }

        ArxivPaper(
            arxivId = arxivId,
            title = entry.selectFirst("title")?.text()?.replace(Regex("\\s+"), " ")?.trim() ?: "",
            authors = entry.select("author > name").eachText(),
            summary = entry.selectFirst("summary")?.text()?.trim() ?: "",
            primaryCategory = entry.getElementsByTag("arxiv:primary_category").firstOrNull()?.attr("term") ?: "",
            categories = entry.select("category").eachAttr("term"),
            pdfUrl = entry.select("link[title=pdf]").firstOrNull()?.attr("href"),
            entryId = entryId,
            published = entry.selectFirst("published")?.text()?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            },
            updated = entry.selectFirst("updated")?.text()?.let { runCatching { Instant.parse(it) }.getOrNull() },
            authorComment = entry.getElementsByTag("arxiv:comment").firstOrNull()?.text(),
            journalRef = entry.getElementsByTag("arxiv:journal_ref").firstOrNull()?.text(),
        )
    }
}
