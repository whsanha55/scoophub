package com.scoophub.auth

import com.scoophub.config.ScoophubProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

/** Google OAuth Authorization Code flow (legacy authlib 대체) */
@Component
class GoogleOAuthClient(
    private val props: ScoophubProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val restClient = restClientBuilder.build()

    fun authorizeUrl(state: String): String = UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
        .queryParam("response_type", "code")
        .queryParam("client_id", props.auth.googleClientId)
        .queryParam("redirect_uri", props.auth.oauthRedirectUri)
        .queryParam("scope", SCOPE)
        .queryParam("state", state)
        .encode()
        .toUriString()

    /** authorization code → access token → userinfo */
    fun exchangeCode(code: String): UserInfo {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", props.auth.oauthRedirectUri)
            add("client_id", props.auth.googleClientId)
            add("client_secret", props.auth.googleClientSecret)
        }
        val token = restClient.post().uri(TOKEN_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body<TokenResponse>()
            ?: error("empty token response")
        return restClient.get().uri(USERINFO_URL)
            .headers { it.setBearerAuth(token.accessToken) }
            .retrieve()
            .body<UserInfo>()
            ?: error("empty userinfo response")
    }

    data class TokenResponse(val accessToken: String)

    data class UserInfo(val email: String?, val name: String?)

    companion object {
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val SCOPE = "openid email profile"
    }
}
