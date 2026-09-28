package com.scoophub.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

/** @Async 활성화 — executor 는 가상 스레드(spring.threads.virtual.enabled) 자동 구성 사용 */
@Configuration
@EnableAsync
class AsyncConfig
