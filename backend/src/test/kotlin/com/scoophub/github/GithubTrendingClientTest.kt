package com.scoophub.github

import com.scoophub.external.github.GithubTrendingClient
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** github.com/trending 마크업 파싱 검증 — 스프링 컨텍스트 없는 순수 단위 */
class GithubTrendingClientTest {
    private val client = GithubTrendingClient()

    @Test
    fun `Jsoup 파싱 — trending HTML 구조`() {
        // given — github.com/trending 마크업 축약본
        val html = """
            <html><body>
            <article class="Box-row">
              <h2><a href="/jetbrains/kotlin">jetbrains / kotlin</a></h2>
              <p class="col-9 color-fg-muted my-1 pr-4">The Kotlin Programming Language. </p>
              <span itemprop="programmingLanguage">Kotlin</span>
              <a href="/jetbrains/kotlin/stargazers">48,000</a>
              <a href="/jetbrains/kotlin/forks">5,900</a>
              <span class="d-inline-block float-sm-right">120 stars today</span>
            </article>
            <article class="Box-row">
              <h2><a href="/spring-projects/spring-boot">spring-projects / spring-boot</a></h2>
              <p class="col-9">Spring Boot helps you create applications...</p>
              <span itemprop="programmingLanguage">Java</span>
              <a href="/spring-projects/spring-boot/stargazers">76,123</a>
              <a href="/spring-projects/spring-boot/forks">40,111</a>
              <span class="d-inline-block float-sm-right">45 stars today</span>
            </article>
            </body></html>
        """.trimIndent()

        // when
        val repos = client.parse(Jsoup.parse(html))

        // then
        assertThat(repos).hasSize(2)
        val kotlin = repos.first()
        assertThat(kotlin.fullname).isEqualTo("jetbrains/kotlin")
        assertThat(kotlin.url).isEqualTo("https://github.com/jetbrains/kotlin")
        assertThat(kotlin.stars).isEqualTo(48000)
        assertThat(kotlin.forks).isEqualTo(5900)
        assertThat(kotlin.currentPeriodStars).isEqualTo(120)
        assertThat(kotlin.language).isEqualTo("Kotlin")
        assertThat(kotlin.description).contains("Kotlin Programming Language")
    }
}
