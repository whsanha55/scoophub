package com.scoophub.external.wttr

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** wttr.in — 서울 현재 날씨 + 주간 예보 (format=j1 원문) */
@Component
class WttrClient(restClientBuilder: RestClient.Builder, private val jsonMapper: JsonMapper) {
    private val restClient = restClientBuilder.baseUrl("https://wttr.in").build()

    // wttr.in 은 JSON 본문을 Content-Type text/plain 으로 내려준다 → 문자열로 받아 직접 파싱
    fun seoul(): JsonNode = restClient.get()
        .uri("/Seoul?format=j1")
        .retrieve()
        .body(String::class.java)
        ?.let(jsonMapper::readTree)
        ?: error("empty wttr.in response")
}
