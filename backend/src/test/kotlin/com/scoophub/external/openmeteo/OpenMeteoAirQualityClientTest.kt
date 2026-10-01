package com.scoophub.external.openmeteo

import com.scoophub.global.config.ClockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

@RestClientTest(OpenMeteoAirQualityClient::class)
@Import(ClockConfig::class)
class OpenMeteoAirQualityClientTest @Autowired constructor(
    private val client: OpenMeteoAirQualityClient,
    private val server: MockRestServiceServer,
) {
    @Test
    fun `timezone 을 이중 인코딩하지 않는다`() {
        // given
        server.expect(queryParam("timezone", "Asia/Seoul"))
            .andRespond(
                withSuccess(
                    """{"hourly":{"time":["2026-10-01T15:00"],"pm10":[20.0],"pm2_5":[10.0],"ozone":[50.0],"uv_index":[1.0]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when
        val air = client.seoulCurrent()

        // then
        assertThat(air?.pm10).isEqualTo(20.0)
        server.verify()
    }
}
