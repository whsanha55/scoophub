package com.scoophub.external.devto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

@RestClientTest(DevtoClient::class)
class DevtoClientTest @Autowired constructor(
    private val client: DevtoClient,
    private val server: MockRestServiceServer,
) {
    @Test
    fun `User-Agent 헤더를 붙여 호출한다`() {
        // given
        server.expect(requestTo("https://dev.to/api/articles?tag=python&top=7&per_page=3"))
            .andExpect(header(HttpHeaders.USER_AGENT, DevtoClient.USER_AGENT))
            .andRespond(withSuccess("""[{"id":1}]""", MediaType.APPLICATION_JSON))

        // when
        val data = client.articlesByTag("python", perPage = 3)

        // then
        assertThat(data[0]["id"].asInt()).isEqualTo(1)
        server.verify()
    }
}
