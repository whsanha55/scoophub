package com.scoophub.external.wttr

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/** wttr.in — 서울 현재 날씨 + 주간 예보 (format=j1 원문) */
@Component
class WttrClient(restClientBuilder: RestClient.Builder) {
    private val restClient = restClientBuilder.baseUrl("https://wttr.in").build()

    fun seoul(): JsonNode = restClient.get()
        .uri("/Seoul?format=j1")
        .retrieve()
        .body(JsonNode::class.java)
        ?: error("empty wttr.in response")
}
