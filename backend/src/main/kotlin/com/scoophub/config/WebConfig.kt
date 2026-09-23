package com.scoophub.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {

    /** legacy CORSMiddleware 와 동일. Spring Security 가 `corsConfigurationSource` 빈을 사용한다. */
    @Bean
    fun corsConfigurationSource(props: ScoophubProperties): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = props.corsOrigins
            allowCredentials = true
            addAllowedMethod("*")
            addAllowedHeader("*")
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    /** FastAPI `/docs` 호환 — springdoc 경로 설정은 application.yml 참고 */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/docs", "/docs/swagger-ui/index.html")
        registry.addViewController("/docs/swagger-config").setViewName("forward:/openapi.json/swagger-config")
    }
}
