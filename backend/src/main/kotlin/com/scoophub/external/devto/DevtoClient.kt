package com.scoophub.external.devto

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/** Dev.to API — 태그별 트렌딩 아티클 */
@Component
class DevtoClient(restClientBuilder: RestClient.Builder) {
    private val restClient = restClientBuilder
        .baseUrl("https://dev.to/api")
        .build()

    fun articlesByTag(tag: String, topDays: Int = 7, perPage: Int): JsonNode = restClient.get()
        .uri("/articles?tag={tag}&top={top}&per_page={perPage}", tag, topDays, perPage)
        .retrieve()
        .body(JsonNode::class.java)
        ?: error("empty devto response")
}
