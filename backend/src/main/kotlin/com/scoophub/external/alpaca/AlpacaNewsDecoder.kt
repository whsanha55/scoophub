package com.scoophub.external.alpaca

import com.scoophub.news.vo.AlpacaArticleRow
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.Locale

@Component
class AlpacaNewsDecoder {
    fun decode(node: JsonNode): AlpacaArticleRow {
        require(node.path("T").asText() == "n") { "Not a news message" }
        val id = node.path("id").asLong(-1)
        val headline = node.path("headline").asText("")
        require(id > 0 && headline.isNotBlank()) { "Invalid news identity" }
        return AlpacaArticleRow(
            id = id, source = node.path("source").asText("benzinga"), headline = headline,
            summary = text(
                node,
                "summary",
            ),
            content = text(node, "content"), author = text(node, "author"), url = text(node, "url"),
            symbols = node.path("symbols").toList().map { it.asText().uppercase(Locale.ROOT) }.distinct(),
            publishedAt = Instant.parse(node.path("created_at").asText()),
            sourceUpdatedAt = Instant.parse(node.path("updated_at").asText()),
        )
    }

    private fun text(node: JsonNode, field: String): String? = node[field]?.takeUnless { it.isNull }?.asText()
}
