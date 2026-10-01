package com.scoophub.external.devto

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/** Dev.to API — 태그별 트렌딩 아티클 */
@Component
class DevtoClient(restClientBuilder: RestClient.Builder) {
    // Dev.to 는 User-Agent 없음/기본 Java UA 요청을 403 으로 막는다
    private val restClient = restClientBuilder
        .baseUrl("https://dev.to/api")
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .build()

    fun articlesByTag(tag: String, topDays: Int = 7, perPage: Int): JsonNode = restClient.get()
        .uri("/articles?tag={tag}&top={top}&per_page={perPage}", tag, topDays, perPage)
        .retrieve()
        .body(JsonNode::class.java)
        ?: error("empty devto response")

    companion object {
        const val USER_AGENT = "scoophub/1.0 (+https://github.com/whsanha55/scoophub)"
    }
}
