package com.scoophub.external.google.client

import com.scoophub.external.google.dto.GoogleTokenResponse
import com.scoophub.external.google.dto.GoogleUserInfo
import com.scoophub.global.config.ScoophubProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

/** Google OAuth Authorization Code flow (legacy authlib 대체). timeout 은 RestClientConfig 공통 적용. */
@Component
class GoogleOAuthClient(private val props: ScoophubProperties, restClientBuilder: RestClient.Builder) {
    private val restClient = restClientBuilder.build()

    fun authorizeUrl(state: String): String = UriComponentsBuilder.fromUriString(props.endpoints.googleAuthorize)
        .queryParam("response_type", "code")
        .queryParam("client_id", props.auth.googleClientId)
        .queryParam("redirect_uri", props.auth.oauthRedirectUri)
        .queryParam("scope", SCOPE)
        .queryParam("state", state)
        .encode()
        .toUriString()

    /** authorization code → access token → userinfo */
    fun exchangeCode(code: String): GoogleUserInfo {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", props.auth.oauthRedirectUri)
            add("client_id", props.auth.googleClientId)
            add("client_secret", props.auth.googleClientSecret)
        }
        val token = restClient.post().uri(props.endpoints.googleToken)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body<GoogleTokenResponse>()
            ?: error("empty token response")
        return restClient.get().uri(props.endpoints.googleUserinfo)
            .headers { it.setBearerAuth(token.accessToken) }
            .retrieve()
            .body<GoogleUserInfo>()
            ?: error("empty userinfo response")
    }

    companion object {
        private const val SCOPE = "openid email profile"
    }
}
