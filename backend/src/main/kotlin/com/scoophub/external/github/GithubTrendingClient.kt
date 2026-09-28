package com.scoophub.external.github

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

data class TrendingRepo(
    val fullname: String,
    val author: String,
    val name: String,
    val url: String,
    val description: String?,
    val language: String?,
    val stars: Int,
    val forks: Int,
    val currentPeriodStars: Int,
)

/** legacy `gtrending` 대체 — github.com/trending HTML 스크래핑 (Jsoup) */
@Component
class GithubTrendingClient {

    fun trending(language: String?, since: String): List<TrendingRepo> {
        val url = if (language.isNullOrBlank()) {
            "https://github.com/trending?since=$since"
        } else {
            "https://github.com/trending/$language?since=$since"
        }
        val doc: Document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; scoophub/1.0)")
            .timeout(15_000)
            .get()
        return parse(doc)
    }

    /** HTML → repo 목록. 테스트를 위해 문서 주입 분리 */
    fun parse(doc: Document): List<TrendingRepo> = doc.select("article.Box-row").mapNotNull { row ->
        val link = row.selectFirst("h2 a[href^=/]") ?: return@mapNotNull null
        val path = link.attr("href").trim('/') // "owner/repo"
        if (path.isBlank() || !path.contains('/')) {
            return@mapNotNull null
        }
        val (author, name) = path.split("/", limit = 2)

        TrendingRepo(
            fullname = path,
            author = author,
            name = name,
            url = "https://github.com/$path",
            description = row.selectFirst("p.col-9")?.text()?.trim()?.ifEmpty { null },
            language = row.selectFirst("span[itemprop=programmingLanguage]")?.text()?.trim()?.ifEmpty { null },
            stars = row.selectFirst("a[href$=/stargazers]")?.text().toIntOrZero(),
            forks = row.selectFirst("a[href$=/forks]")?.text().toIntOrZero(),
            currentPeriodStars = row.selectFirst("span.d-inline-block.float-sm-right")
                ?.text()
                ?.filter { it.isDigit() }
                ?.ifEmpty { null }
                ?.toInt()
                ?: 0,
        )
    }.also {
        if (it.isEmpty()) {
            log.warn { "github trending 파싱 결과 0건 — 마크업 변경 확인 필요" }
        }
    }

    private fun String?.toIntOrZero(): Int = this?.filter { it.isDigit() }?.ifEmpty { null }?.toInt() ?: 0
}
