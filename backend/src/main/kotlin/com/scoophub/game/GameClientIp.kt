package com.scoophub.game

/**
 * 운영 경로는 Cloudflare → Caddy → (Next →) 백엔드다(ADR-0003). X-Forwarded-For 의 첫 값은
 * 클라이언트가 마음대로 넣을 수 있으므로 쓰지 않는다. Cloudflare 가 실제 접속 IP 로 덮어쓰는
 * CF-Connecting-IP 를 쓰고, 없으면(로컬) 직접 접속한 주소를 쓴다.
 */
const val CLIENT_IP_HEADER = "CF-Connecting-IP"

fun resolveClientIp(cfConnectingIp: String?, remoteAddr: String?): String =
    cfConnectingIp?.trim()?.takeIf { it.isNotEmpty() } ?: remoteAddr ?: "unknown"
