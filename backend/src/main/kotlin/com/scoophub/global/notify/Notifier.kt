package com.scoophub.global.notify

/**
 * 발신 단위 메시지. text 는 Telegram HTML (parse_mode=HTML).
 * 동적 텍스트(category 등 외부값)는 발신 전 [NotifyCard.escapeHtml] 적용 필수.
 */
data class NotifyMessage(val text: String)

/** 발신 채널 인터페이스. 신규 채널(Discord/Email)은 이것을 구현하는 클래스 1개 추가 */
interface Notifier {
    val channel: String

    /** chat_id 의 topicId(포럼 토픽, null=일반) 로 message 전송 */
    fun send(chatId: String, topicId: Long?, message: NotifyMessage)

    /** 새 포럼 토픽 생성 → message_thread_id 반환 */
    fun createTopic(chatId: String, name: String): Long
}
