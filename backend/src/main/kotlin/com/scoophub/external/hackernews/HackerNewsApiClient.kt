package com.scoophub.external.hackernews

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/** Hacker News Firebase API — top/best stories, item */
@Component
class HackerNewsApiClient(restClientBuilder: RestClient.Builder) {
    private val restClient = restClientBuilder
        .baseUrl("https://hacker-news.firebaseio.com/v0")
        .build()

    fun storyIds(storyType: String): JsonNode = restClient.get()
        .uri("/${storyType}stories.json")
        .retrieve()
        .body(JsonNode::class.java)
        ?: error("empty $storyType stories response")

    fun item(id: Long): JsonNode? = restClient.get()
        .uri("/item/{id}.json", id)
        .retrieve()
        .body(JsonNode::class.java)
}
