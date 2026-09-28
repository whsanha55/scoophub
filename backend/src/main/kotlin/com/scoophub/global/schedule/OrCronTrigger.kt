package com.scoophub.global.schedule

import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.support.CronTrigger
import java.time.Instant
import java.time.ZoneId

/** legacy `OrTrigger` — 다중 cron expr 중 가장 빠른 다음 실행 하나만 발화 */
class OrCronTrigger(private val triggers: List<CronTrigger>) : Trigger {
    override fun nextExecution(triggerContext: TriggerContext): Instant? = triggers.asSequence()
        .mapNotNull { it.nextExecution(triggerContext) }
        .minOrNull()
}

object CronTriggers {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * cron 5필드 crontab → Spring 6필드(`"0 " + expr`), Asia/Seoul (#174 와 동일).
     * 단일 expr 는 [CronTrigger] 그대로, 다중은 [OrCronTrigger] 로 결합한다.
     */
    fun of(expressions: List<String>, zone: ZoneId = SEOUL): Trigger = if (expressions.size == 1) {
        CronTrigger("0 ${expressions[0]}", zone)
    } else {
        OrCronTrigger(expressions.map { CronTrigger("0 $it", zone) })
    }
}
