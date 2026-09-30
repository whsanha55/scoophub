package com.scoophub.arxiv.dto

import com.scoophub.global.crawl.vo.BatchRow
import com.scoophub.global.jackson.scalar
import tools.jackson.databind.JsonNode

/** legacy `_arxiv_item` — crawl_data row → arxiv 응답 필드로 재구성 */
data class ArxivItem(
    val id: Long,
    val arxivId: String?,
    val title: String?,
    val authors: List<String>?,
    val summary: String?,
    val primaryCategory: String?,
    val categories: List<String>?,
    val pdfUrl: String?,
    val abstractUrl: String?,
    val publishedAt: String?,
    val updatedAt: String?,
    val authorComment: String?,
    val journalRef: String?,
    val fetchedAt: String?,
) {
    companion object {
        fun from(row: BatchRow) = with(row.response) {
            ArxivItem(
                id = row.id,
                arxivId = scalar("arxiv_id"),
                title = scalar("title"),
                authors = this["authors"].stringList(),
                summary = scalar("summary"),
                primaryCategory = scalar("primary_category"),
                categories = this["categories"].stringList(),
                pdfUrl = scalar("pdf_url"),
                abstractUrl = scalar("abstract_url"),
                publishedAt = scalar("published_at"),
                updatedAt = scalar("updated_at"),
                authorComment = scalar("author_comment"),
                journalRef = scalar("journal_ref"),
                fetchedAt = scalar("fetched_at"),
            )
        }

        private fun JsonNode?.stringList(): List<String>? = this?.takeIf { it.isArray }?.toList()?.map { it.asText() }
    }
}
