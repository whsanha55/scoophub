package com.scoophub.stock.vo

/** ±1σ 예상변동폭 구간 — straddle 스냅샷 기반 (center=현재가) */
data class SigmaRange(
    val center: Double = 0.0,
    val upper1sigma: Double = 0.0,
    val lower1sigma: Double = 0.0,
    val currentPrice: Double = 0.0,
)
