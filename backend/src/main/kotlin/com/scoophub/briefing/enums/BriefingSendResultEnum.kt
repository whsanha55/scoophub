package com.scoophub.briefing.enums

enum class BriefingSendResultEnum {
    SENT,

    /** 기준을 넘은 새 항목이 없어 보내지 않았다 */
    EMPTY,

    /** 라우트가 없거나 발송이 실패했다. 항목을 기록하지 않아 다음 회차 후보로 남는다 */
    FAILED,
}
