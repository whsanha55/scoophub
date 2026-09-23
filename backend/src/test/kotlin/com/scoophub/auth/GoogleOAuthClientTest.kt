package com.scoophub.auth

import com.scoophub.config.ScoophubProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RestClientTest(GoogleOAuthClient::class)
@EnableConfigurationProperties(ScoophubProperties::class)
class GoogleOAuthClientTest @Autowired constructor(
    private val client: GoogleOAuthClient,
    private val server: MockRestServiceServer,
) {
    @Test
    fun `authorize URL 에 client_id, redirect_uri, scope, state 포함`() {
        val url = client.authorizeUrl("abc")

        assertTrue(url.startsWith(GoogleOAuthClient.AUTHORIZE_URL + "?response_type=code"))
        assertTrue("scope=openid%20email%20profile" in url)
        assertTrue("state=abc" in url)
    }

    @Test
    fun `code 교환 후 access_token 으로 userinfo 조회`() {
        server.expect(requestTo(GoogleOAuthClient.TOKEN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(mapOf("grant_type" to "authorization_code", "code" to "c")))
            .andRespond(withSuccess("""{"access_token":"at","expires_in":3599}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(GoogleOAuthClient.USERINFO_URL))
            .andExpect(header("Authorization", "Bearer at"))
            .andRespond(
                withSuccess("""{"sub":"1","email":"a@b.com","name":"A","picture":"x"}""", MediaType.APPLICATION_JSON),
            )

        assertEquals(GoogleOAuthClient.UserInfo("a@b.com", "A"), client.exchangeCode("c"))
        server.verify()
    }
}
