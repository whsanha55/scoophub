package com.scoophub.news.service

import com.scoophub.news.vo.AlpacaArticleRow
import org.springframework.stereotype.Component

@Component
class NewsRuleFilter {
    private val noise = listOf(
        "stocks-to-watch" to Regex("stocks to watch", RegexOption.IGNORE_CASE),
        "transcript" to Regex("^transcript:", RegexOption.IGNORE_CASE),
        "crypto-price-prediction" to
            Regex("(bitcoin|crypto|ethereum|dogecoin).*(price prediction|price forecast)", RegexOption.IGNORE_CASE),
        "price-move-explainer" to Regex("^why (is|are) .*\\b(stocks?|shares)\\b", RegexOption.IGNORE_CASE),
        "price-move-explainer" to Regex("shares of .* (are )?trading (higher|lower)", RegexOption.IGNORE_CASE),
        "market-wrap" to Regex("^stock market today", RegexOption.IGNORE_CASE),
        "opinion" to Regex("buying opportunity|here are the", RegexOption.IGNORE_CASE),
    )
    private val macro = Regex(
        "\\b(FOMC|CPI|PCE|Fed|Federal Reserve|inflation|tariffs?|GDP|payrolls?|unemployment|interest rates?|treasury|geopolitical|war|sanctions?)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun exclusionReason(article: AlpacaArticleRow): String? {
        val pattern = noise.firstOrNull { it.second.containsMatchIn(article.headline) }
        if (pattern != null) return "noise:${pattern.first}"
        if (article.symbols.isEmpty() &&
            !macro.containsMatchIn(article.headline + " " + article.summary.orEmpty())
        ) {
            return "no-symbol-or-macro"
        }
        return null
    }
}
