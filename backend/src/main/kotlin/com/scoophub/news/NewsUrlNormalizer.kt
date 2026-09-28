package com.scoophub.news

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

private val log = KotlinLogging.logger {}

/** legacy `news/dedup.py` 의 URL 정규화 — exact-match dedup 용 canonical key */
object NewsUrlNormalizer {

    private val TRACKING_PREFIXES = listOf("utm_")
    private val TRACKING_KEYS = setOf(
        "gclid", "fbclid", "ocid", "oc", "cmpid", "ref", "ref_src",
        "spm", "igshid", "mc_cid", "mc_eid",
    )

    /** lowercase host, 트래킹 파라미터 제거·정렬, fragment/트레일링 슬래시 제거 */
    fun normalize(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) {
            return raw
        }
        return try {
            val uri = URI(raw)
            val scheme = (uri.scheme ?: "https").lowercase()
            val host = (uri.host ?: "").lowercase()
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val path = (uri.path ?: "").trimEnd('/').ifEmpty { "/" }

            val kept = (uri.rawQuery ?: "").split('&')
                .filter { it.isNotEmpty() }
                .mapNotNull { kv ->
                    val idx = kv.indexOf('=')
                    val key = (if (idx >= 0) kv.substring(0, idx) else kv).lowercase()
                    if (TRACKING_PREFIXES.any { key.startsWith(it) } || key in TRACKING_KEYS) {
                        null
                    } else {
                        kv
                    }
                }
                .sorted()
            val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")

            "$scheme://$host$port$path$query"
        } catch (e: Exception) {
            log.warn { "URL 파싱 실패 — 원문 반환: $raw" }
            raw
        }
    }
}
