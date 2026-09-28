package com.scoophub.external.producthunt

import com.scoophub.global.config.ScoophubProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/** Product Hunt GraphQL API v2 — RANKING 순 오늘 게시물 */
@Component
class ProductHuntClient(props: ScoophubProperties, restClientBuilder: RestClient.Builder, private val clock: Clock) {
    private val token = props.producthuntToken
    private val restClient = restClientBuilder
        .baseUrl("https://api.producthunt.com/v2/api/graphql")
        .build()

    fun todayPosts(maxPosts: Int): JsonNode {
        if (token.isEmpty()) {
            throw IllegalStateException("PRODUCTHUNT_TOKEN not configured")
        }
        // 오늘 00:00 UTC 기준
        val today = clock.instant().truncatedTo(ChronoUnit.DAYS).toString()
        val body = restClient.post()
            .contentType(MediaType.APPLICATION_JSON)
            .headers { it.setBearerAuth(token) }
            .body(
                mapOf(
                    "query" to GRAPHQL_QUERY,
                    "variables" to mapOf("first" to maxPosts, "postedAfter" to today),
                ),
            )
            .retrieve()
            .body(JsonNode::class.java)
            ?: throw IllegalStateException("empty Product Hunt response")
        log.info { "Product Hunt posts 조회 완료" }
        return body
    }

    companion object {
        private val GRAPHQL_QUERY = """
            query(${'$'}first: Int!, ${'$'}postedAfter: DateTime) {
              posts(order: RANKING, first: ${'$'}first, postedAfter: ${'$'}postedAfter) {
                edges {
                  node {
                    id
                    name
                    tagline
                    slug
                    url
                    website
                    votesCount
                    commentsCount
                    featuredAt
                    createdAt
                    topics {
                      edges {
                        node {
                          name
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
