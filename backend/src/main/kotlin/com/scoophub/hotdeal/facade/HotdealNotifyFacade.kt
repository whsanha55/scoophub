package com.scoophub.hotdeal.facade

import com.scoophub.global.config.ScoophubProperties
import com.scoophub.global.notify.AutoTopicProvisioner
import com.scoophub.global.notify.NotifyRouter
import com.scoophub.global.schedule.ScheduleResolver
import com.scoophub.hotdeal.service.HotdealNotifyService
import com.scoophub.hotdeal.vo.HotdealNotifySettings
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * 추천 항목 Telegram 알림 (9절). 설정은 crawl_config('hotdeal_notify').
 * 발송 성공으로 확인된 항목만 notified_at 을 남긴다. 실패한 항목은 다음 주기에 다시 후보가 되고,
 * 같은 항목의 성공 재발송은 NotifyRouter 의 notify_log (route, payload key) 확인으로 막힌다.
 */
@Component
class HotdealNotifyFacade(
    private val notifyService: HotdealNotifyService,
    private val scheduleResolver: ScheduleResolver,
    private val router: NotifyRouter,
    private val provisioner: AutoTopicProvisioner,
    private val props: ScoophubProperties,
    private val clock: Clock,
) {

    /** 보낸 건수 */
    fun notifyRecommendations(): Int {
        if (props.telegram.botToken.isEmpty()) {
            return 0
        }
        val now = clock.instant()
        val settings = HotdealNotifySettings.from(scheduleResolver.resolveParams(CONFIG_KEY))
        val outgoing = notifyService.findOutgoing(settings, now)
        if (outgoing.isEmpty()) {
            return 0
        }
        try {
            provisioner.ensureRoute(CATEGORY, PURPOSE)
        } catch (e: Exception) {
            log.warn { "hotdeal notify route provision failed: ${e.message}" }
        }
        var sent = 0
        for (message in outgoing) {
            if (router.dispatchConfirmed(CATEGORY, PURPOSE, "hotdeal:${message.feedItemId}", message.message)) {
                notifyService.markNotified(message.feedItemId, now)
                sent++
            }
        }
        log.info { "hotdeal notify done - candidates=${outgoing.size}, sent=$sent" }
        return sent
    }

    companion object {
        const val CONFIG_KEY = "hotdeal_notify"
        const val CATEGORY = "hotdeal"
        const val PURPOSE = "recommend"
    }
}
