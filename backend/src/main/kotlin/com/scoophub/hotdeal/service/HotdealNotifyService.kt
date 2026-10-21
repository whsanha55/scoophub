package com.scoophub.hotdeal.service

import com.scoophub.global.notify.NotifyCard.Companion.escapeHtml
import com.scoophub.global.notify.NotifyMessage
import com.scoophub.hotdeal.repository.HotdealFeedItemRepository
import com.scoophub.hotdeal.vo.HotdealNotifySettings
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.Locale

/** 추천 알림 대상 선정·메시지 구성·발송 기록. 발송은 트랜잭션 밖(HotdealNotifyFacade)에서 한다 */
@Service
class HotdealNotifyService(private val feedItemRepository: HotdealFeedItemRepository) {

    data class Outgoing(val feedItemId: Long, val message: NotifyMessage)

    /** 오늘 남은 상한만큼 보낼 메시지. 조용한 시간·비활성이면 빈 목록 */
    fun findOutgoing(settings: HotdealNotifySettings, now: Instant): List<Outgoing> {
        if (!settings.enabled || settings.isQuiet(now)) {
            return emptyList()
        }
        val remaining =
            settings.dailyLimit - feedItemRepository.countByNotifiedAtGreaterThanEqual(settings.dayStart(now))
        if (remaining <= 0) {
            return emptyList()
        }
        return feedItemRepository.findNotifyCandidates(settings.scoreThreshold, now.minus(settings.maxAge))
            .take(remaining.toInt())
            .map { feedItem ->
                val item = feedItem.item
                val extra = item.extra
                val price = extra.path("price").takeIf { it.isNumber }?.asLong()?.let {
                    if (it == 0L) "무료" else "%,d원".format(Locale.ROOT, it)
                }
                val priceLine = listOfNotNull(price, extra.path("shipping_fee").textOrNull()?.let { "배송 $it" })
                val sourceLine =
                    listOfNotNull(SOURCE_LABELS[item.source.name] ?: item.source.name, extra.path("mall").textOrNull())
                val links = listOfNotNull(
                    """<a href="${escapeHtml(item.url)}">원문</a>""",
                    settings.webUrl.takeIf {
                        it.isNotEmpty()
                    }?.let { """<a href="${escapeHtml(it)}/hotdeal">피드</a>""" },
                )
                val text = listOfNotNull(
                    "<b>🔥 ${escapeHtml(item.title.take(TITLE_MAX))}</b>",
                    priceLine.takeIf { it.isNotEmpty() }?.joinToString(" · ") { escapeHtml(it) },
                    sourceLine.joinToString(" · ") { escapeHtml(it) },
                    escapeHtml(feedItem.matchedTerms.path("reason").asText("")).takeIf { it.isNotEmpty() },
                    links.joinToString(" · "),
                ).joinToString("\n")
                Outgoing(requireNotNull(feedItem.id), NotifyMessage(text))
            }
    }

    @Transactional
    fun markNotified(feedItemId: Long, now: Instant) {
        feedItemRepository.findByIdOrNull(feedItemId)?.markNotified(now)
    }

    private fun JsonNode.textOrNull(): String? = takeIf { !it.isNull && !it.isMissingNode }?.asText()?.ifBlank { null }

    companion object {
        private const val TITLE_MAX = 200
        private val SOURCE_LABELS = mapOf("PPOMPPU" to "뽐뿌", "ZOD" to "알구더")
    }
}
