package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate

private val log = KotlinLogging.logger {}

/**
 * legacy `stock/resample.py` — 1D 캔들 → 주/월 캔들 resample.
 * open=첫 open, high=구간 최대, low=구간 최소, close=마지막 close, volume=합.
 * 부족 시 가짜 분석 영속화 방지를 위해 빈 리스트.
 */
object StockResample {
    // MA20, ADX period*2 등 의미 있는 지표 계산에 필요한 최소 캔들 수
    const val MIN_WEEKLY_CANDLES = 26
    const val MIN_MONTHLY_CANDLES = 20

    private fun aggregate(group: List<Candle>, ruleLabel: String): Candle {
        val first = group.first()
        val last = group.last()
        return Candle(
            ticker = first.ticker,
            interval = ruleLabel,
            date = first.date,
            open = first.open,
            high = group.maxOf { it.high },
            low = group.minOf { it.low },
            close = last.close,
            volume = group.sumOf { it.volume },
        )
    }

    /** 1D → 주봉(W-MON). 빈 입력/부족 시 빈 리스트 */
    fun weekly(daily: List<Candle>): List<Candle> {
        if (daily.isEmpty()) {
            return emptyList()
        }
        val weekly = daily.sortedBy { it.date }
            .groupBy { mondayOf(it.date) }
            .toSortedMap()
            .map { (_, group) -> aggregate(group, "1W") }
        if (weekly.size < MIN_WEEKLY_CANDLES) {
            log.info { "resample_weekly: insufficient weekly candles (${weekly.size} < $MIN_WEEKLY_CANDLES) — skip" }
            return emptyList()
        }
        return weekly
    }

    /** 1D → 월봉(자연월). 빈 입력/부족 시 빈 리스트 */
    fun monthly(daily: List<Candle>): List<Candle> {
        if (daily.isEmpty()) {
            return emptyList()
        }
        val monthly = daily.sortedBy { it.date }
            .groupBy { it.date.year to it.date.monthValue }
            .entries
            .sortedBy { it.value.first().date } // 버킷 첫 캔들 날짜 기준 시간순
            .map { (_, group) -> aggregate(group, "1M") }
        if (monthly.size < MIN_MONTHLY_CANDLES) {
            log.info {
                "resample_monthly: insufficient monthly candles (${monthly.size} < $MIN_MONTHLY_CANDLES) — skip"
            }
            return emptyList()
        }
        return monthly
    }
}
