package com.scoophub.game.enums

/** 서버가 판정해 대국을 끝내는 사유. 규칙 판정(외통수·빅장 등) 사유는 클라이언트가 보낸 문구를 그대로 쓴다(ADR-0001). */
enum class GameEndReasonEnum(val label: String) {
    TIMEOUT("시간패"),
    TIMEOUT_DRAW("시간 초과 무승부(메이트 불가)"),
    RESIGN("기권"),
    ABANDON("상대 장기 이탈"),
}
