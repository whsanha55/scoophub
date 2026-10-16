package com.scoophub.game.config

import com.scoophub.game.service.AbuseLimiter
import com.scoophub.game.service.DefaultAbuseLimiter
import com.scoophub.game.service.NoopAbuseLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock

/** 남용 방지 한도 빈. local 프로파일에서는 끈다 (ADR-0002). */
@Configuration
class AbuseLimiterConfig {
    @Bean
    @Profile("!local")
    fun defaultAbuseLimiter(clock: Clock): AbuseLimiter = DefaultAbuseLimiter(clock)

    @Bean
    @Profile("local")
    fun noopAbuseLimiter(): AbuseLimiter = NoopAbuseLimiter()
}
