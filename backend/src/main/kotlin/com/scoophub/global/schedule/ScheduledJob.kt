package com.scoophub.global.schedule

import tools.jackson.databind.JsonNode

/**
 * legacy `BaseScheduler.register_job` 의 등록 단위. 크롤러 잡(`{name}_crawler`)과
 * stock 분석 잡(stock_sync 등) 모두 이 인터페이스로 스케줄러에 등록된다.
 */
interface ScheduledJob {
    /** crawl_schedule.crawler (crawler, job_id) PK 의 앞쪽 */
    val crawler: String

    /** crawl_schedule.job_id — 스케줄 런타임 식별자 */
    val jobId: String

    /** crawl_config.params 를 받아 실행. 빈 객체인 경우도 있다 */
    fun run(params: JsonNode)
}

/** legacy `resolve_trigger` 가 DB 로부터 만드는 트리거 원형 */
sealed interface ScheduleTrigger {
    /** cron 5필드 expr 배열 (다중 지원) */
    data class Cron(val expressions: List<String>) : ScheduleTrigger

    /** 분 단위 주기. 첫 실행은 interval 후 (APScheduler IntervalTrigger 와 동일) */
    data class Interval(val minutes: Int) : ScheduleTrigger
}
