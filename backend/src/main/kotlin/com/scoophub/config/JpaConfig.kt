package com.scoophub.config

import org.hibernate.cfg.MappingSettings
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

@Configuration
class JpaConfig {

    /**
     * JSONB(`@JdbcTypeCode(SqlTypes.JSON)`) 매핑에 앱 전역 Jackson 3 JsonMapper 사용.
     * 지정하지 않으면 Hibernate 가 classpath 의 Jackson 2 를 골라 Jackson 3 JsonNode 를 못 읽는다.
     */
    @Bean
    fun jsonFormatMapperCustomizer(jsonMapper: JsonMapper) = HibernatePropertiesCustomizer {
        it[MappingSettings.JSON_FORMAT_MAPPER] = Jackson3JsonFormatMapper(jsonMapper)
    }
}
