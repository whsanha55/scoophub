package com.scoophub.global.schedule

import com.scoophub.global.schedule.repository.CrawlConfigRepository
import com.scoophub.global.schedule.repository.CrawlScheduleRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** legacy `BaseScheduler.resolve_trigger` / `resolve_params` — DB → 트리거 원형 + 도메인 파라미터 */
@Component
class ScheduleResolver(
    private val scheduleRepository: CrawlScheduleRepository,
    private val configRepository: CrawlConfigRepository,
    private val jsonMapper: JsonMapper,
) {
    data class Resolved(val trigger: ScheduleTrigger, val enabled: Boolean)

    /** (crawler, job_id) 행으로 트리거와 enabled 를 만든다. 행 없음/빈 cron/비양수 interval 은 예외 */
    fun resolveTrigger(crawler: String, jobId: String): Resolved {
        val row = scheduleRepository.findByCrawlerAndJobId(crawler, jobId)
            ?: throw IllegalArgumentException("crawl_schedule row not found for (crawler='$crawler', job_id='$jobId')")

        return when (row.scheduleType) {
            "cron" -> {
                val exprs = row.schedules
                if (exprs.isEmpty()) {
                    throw IllegalArgumentException("cron schedules empty for (crawler='$crawler', job_id='$jobId')")
                }
                Resolved(ScheduleTrigger.Cron(exprs), row.enabled)
            }

            "interval" -> {
                val minutes = row.scheduleMinutes
                if (minutes == null || minutes <= 0) {
                    throw IllegalArgumentException(
                        "schedule_minutes must be a positive integer for (crawler='$crawler', job_id='$jobId'), got: $minutes",
                    )
                }
                Resolved(ScheduleTrigger.Interval(minutes), row.enabled)
            }

            else -> throw IllegalArgumentException(
                "unknown schedule_type '${row.scheduleType}' for (crawler='$crawler', job_id='$jobId')",
            )
        }
    }

    /**
     * crawl_config(crawler PK)의 params JSONB. 행이 없으면 빈 객체
     * (weather 처럼 params 가 필요 없는 도메인).
     */
    fun resolveParams(crawler: String): JsonNode =
        configRepository.findByCrawler(crawler)?.params ?: jsonMapper.readTree("{}")
}
