package com.scoophub.global.schedule.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
class SchedulingConfig {

    /**
     * 크롤/분석 잡 전용 스케줄러. 잡마다 중복 실행은 일어나지 않으므로(see [com.scoophub.global.schedule.CrawlScheduler]),
     * 풀 크기는 같은 분에 몰려 발화하는 잡 수(4시간 주기 그룹 4종 등)에 맞춘 여유값.
     */
    @Bean
    fun crawlTaskScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        // Spring 7 + Kotlin: 재선언된 setter 가 프로퍼티와 짝지어지지 않아 명시 호출
        setPoolSize(8)
        setThreadNamePrefix("crawl-sched-")
        setRemoveOnCancelPolicy(true)
    }
}
