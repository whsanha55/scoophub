package com.scoophub.stock.vo

import java.time.LocalDate

/** 현재가와 당일 시세. change 는 전일 종가 대비 */
data class Quote(
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
)

data class OptionQuote(val strike: Double, val bid: Double, val ask: Double, val lastPrice: Double, val volume: Long)

/** 한 만기의 옵션 체인 */
data class OptionsChain(val expiry: LocalDate, val calls: List<OptionQuote>, val puts: List<OptionQuote>)

/** 종목별 시세 수집 결과. failures 는 ticker → 실패 사유 */
data class FetchOutcome(val total: Int, val saved: Int, val failures: Map<String, String>)
