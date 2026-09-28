package com.scoophub.global.schedule

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.support.CronTrigger
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.ZonedDateTime

/** legacy `OrTrigger`/`CronTrigger.from_crontab` 변환 포팅 검증 */
class OrCronTriggerTest {

    @Test
    fun `단일 expr 는 6필드 CronTrigger 로 변환한다`() {
        // when
        val trigger = CronTriggers.of(listOf("30 22 * * 2-6"))

        // then
        assertThat(trigger).isInstanceOf(CronTrigger::class.java)
        assertThat((trigger as CronTrigger).expression).isEqualTo("0 30 22 * * 2-6")
    }

    @Test
    fun `다중 expr 은 가장 빠른 다음 실행을 고른다`() {
        // given
        val trigger = CronTriggers.of(listOf("0 9 * * *", "0 21 * * *"))
        val completedAt = ZonedDateTime.of(2027, 1, 1, 10, 0, 0, 0, CronTriggers.SEOUL).toInstant()

        // when
        val next = trigger.nextExecution(SimpleTriggerContext(completedAt, completedAt, completedAt))

        // then — 같은 날 21:00 KST
        assertThat(next).isEqualTo(ZonedDateTime.of(2027, 1, 1, 21, 0, 0, 0, CronTriggers.SEOUL).toInstant())
    }

    @Test
    fun `모든 expr 를 지났으면 다음날 첫 expr 를 고른다`() {
        // given
        val trigger = CronTriggers.of(listOf("0 9 * * *", "0 21 * * *"))
        val completedAt = ZonedDateTime.of(2027, 1, 1, 22, 30, 0, 0, CronTriggers.SEOUL).toInstant()

        // when
        val next = trigger.nextExecution(SimpleTriggerContext(completedAt, completedAt, completedAt))

        // then — 다음날 09:00 KST
        assertThat(next).isEqualTo(ZonedDateTime.of(2027, 1, 2, 9, 0, 0, 0, CronTriggers.SEOUL).toInstant())
    }
}
