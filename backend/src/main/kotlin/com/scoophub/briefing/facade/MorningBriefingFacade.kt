package com.scoophub.briefing.facade

import com.scoophub.briefing.enums.BriefingSendResultEnum
import com.scoophub.briefing.service.MorningBriefingService
import com.scoophub.briefing.vo.MorningBriefingSettings
import com.scoophub.global.notify.AutoTopicProvisioner
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.global.schedule.ScheduleResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * 아침 브리핑 발송. 설정은 crawl_config('morning_briefing') 를 발송할 때마다 다시 읽는다.
 * 발송이 확인된 회차의 항목만 "이미 나온 항목"으로 기록한다. 실패하면 다음 회차 후보로 남는다.
 */
@Component
class MorningBriefingFacade(
    private val briefingService: MorningBriefingService,
    private val scheduleResolver: ScheduleResolver,
    private val router: NotifyRouter,
    private val provisioner: AutoTopicProvisioner,
    private val clock: Clock,
) {

    fun send(): BriefingSendResultEnum {
        val now = clock.instant()
        val settings = MorningBriefingSettings.from(scheduleResolver.resolveParams(CONFIG_KEY))
        val briefing = briefingService.compose(settings, now)
        if (briefing == null) {
            log.info { "morning briefing skipped - no new item over threshold" }
            return BriefingSendResultEnum.EMPTY
        }
        try {
            provisioner.ensureRoute(CATEGORY, PURPOSE)
        } catch (e: Exception) {
            log.warn { "morning briefing route provision failed: ${e.message}" }
        }
        // 항목 중복은 "이미 나온 항목" 기록이 막으므로 발송 키는 회차마다 새로 만든다.
        // 날짜 키를 쓰면 같은 날 다시 실행할 때 라우터가 성공 이력을 보고 건너뛰어, 보내지 않은 항목이 기록된다.
        // 블록을 따로 넘기면 라우터가 Telegram 길이 한도 안에서 빈 줄로 이어 보낸다.
        val messages = briefing.blocks().mapIndexed { i, block -> "$CATEGORY:$now:$i" to NotifyMessage(block) }
        if (!router.dispatchBatch(CATEGORY, PURPOSE, messages)) {
            log.warn { "morning briefing send failed - items=${briefing.briefingKeys.size}" }
            return BriefingSendResultEnum.FAILED
        }
        briefingService.markBriefed(briefing.briefingKeys, now)
        log.info { "morning briefing sent - items=${briefing.briefingKeys.size}" }
        return BriefingSendResultEnum.SENT
    }

    companion object {
        const val CONFIG_KEY = "morning_briefing"
        const val CATEGORY = "morning_briefing"
        const val PURPOSE = "daily"
    }
}
