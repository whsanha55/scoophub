package com.scoophub.global.config

import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.http.HttpClient
import java.time.Duration

/**
 * external 클라이언트가 쓰는 RestClient.Builder 공통 timeout.
 * @RestClientTest 가 만드는 mock builder 에는 적용되지 않는다(의도된 동작).
 */
@Configuration
class RestClientConfig {

    @Bean
    fun timeoutRestClientCustomizer(): RestClientCustomizer = RestClientCustomizer { builder ->
        builder.requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
            ).apply { setReadTimeout(READ_TIMEOUT) },
        )
    }

    companion object {
        private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
        private val READ_TIMEOUT = Duration.ofSeconds(10)
    }
}
