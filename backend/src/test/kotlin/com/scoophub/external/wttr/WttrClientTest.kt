package com.scoophub.external.wttr

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

@RestClientTest(WttrClient::class)
class WttrClientTest @Autowired constructor(
    private val client: WttrClient,
    private val server: MockRestServiceServer,
) {
    @Test
    fun `text plain 으로 내려온 JSON 본문을 파싱한다`() {
        // given
        server.expect(requestTo("https://wttr.in/Seoul?format=j1"))
            .andRespond(withSuccess("""{"current_condition":[{"temp_C":"15"}]}""", MediaType.TEXT_PLAIN))

        // when
        val data = client.seoul()

        // then
        assertThat(data["current_condition"][0]["temp_C"].asString()).isEqualTo("15")
        server.verify()
    }
}
