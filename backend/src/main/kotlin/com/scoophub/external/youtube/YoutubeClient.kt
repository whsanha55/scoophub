package com.scoophub.external.youtube

import com.scoophub.global.config.ScoophubProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/** YouTube Data API v3 videos.list (legacy googleapiclient 대체 — REST 직접) */
@Component
class YoutubeClient(props: ScoophubProperties, restClientBuilder: RestClient.Builder) {
    private val apiKey = props.youtubeApiKey
    private val restClient = restClientBuilder
        .baseUrl("https://www.googleapis.com/youtube/v3")
        .build()

    fun mostPopular(regionCode: String, maxResults: Int): JsonNode {
        if (apiKey.isEmpty()) {
            throw IllegalStateException("YOUTUBE_API_KEY not configured")
        }
        return restClient.get()
            .uri(
                "/videos?chart=mostPopular&part=snippet,contentDetails,statistics" +
                    "&regionCode={region}&maxResults={max}&key={key}",
                regionCode,
                maxResults,
                apiKey,
            )
            .retrieve()
            .body(JsonNode::class.java)
            ?: error("empty youtube response")
    }
}
