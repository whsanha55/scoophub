package com.scoophub.global.jackson

import tools.jackson.databind.JsonNode

/**
 * 객체 필드를 스칼라 문자열로. null/missing/NullNode 이면 null.
 * python `dict.get` 관용구 대응.
 */
fun JsonNode?.scalar(field: String): String? {
    val node = this?.get(field) ?: return null
    if (node.isNull || node.isMissingNode) {
        return null
    }
    return node.asText()
}

/**
 * 배열 노드를 List 로. null/missing/배열 아님 → 빈 리스트.
 * nullable 리시버 선언으로 Jackson 자체 `elements()`(Iterator) 멤버와의 충돌을 피한다.
 */
fun JsonNode?.elements(): List<JsonNode> = this?.takeIf { it.isArray }?.toList() ?: emptyList()
