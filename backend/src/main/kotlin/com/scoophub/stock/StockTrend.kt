package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import java.time.LocalDate

enum class TrendStateEnum { GOLDEN, DEAD, UNKNOWN }

/** since 는 마지막 교차일. 판단 가능해진 뒤로 교차가 없었으면 null */
data class TrendStatus(val state: TrendStateEnum, val since: LocalDate?)

/**
 * 50/200 이동평균 골든·데드크로스 추세 판정.
 * 지표 점수 합산 매수·매도 신호는 백테스트에서 예측력이 없어 이 규칙으로 대체했다 (#251).
 */
object StockTrend {
    const val FAST_PERIOD = 50
    const val SLOW_PERIOD = 200

    /** 캔들별 상태. SMA200 을 구할 수 없는 앞 199개는 UNKNOWN */
    fun states(candles: List<Candle>): List<TrendStateEnum> {
        var fastSum = 0.0
        var slowSum = 0.0
        return candles.indices.map { i ->
            fastSum += candles[i].close
            slowSum += candles[i].close
            if (i >= FAST_PERIOD) {
                fastSum -= candles[i - FAST_PERIOD].close
            }
            if (i >= SLOW_PERIOD) {
                slowSum -= candles[i - SLOW_PERIOD].close
            }
            when {
                i < SLOW_PERIOD - 1 -> TrendStateEnum.UNKNOWN
                fastSum / FAST_PERIOD > slowSum / SLOW_PERIOD -> TrendStateEnum.GOLDEN
                else -> TrendStateEnum.DEAD
            }
        }
    }

    fun evaluate(candles: List<Candle>): TrendStatus {
        val states = states(candles)
        val current = states.lastOrNull() ?: TrendStateEnum.UNKNOWN
        if (current == TrendStateEnum.UNKNOWN) {
            return TrendStatus(TrendStateEnum.UNKNOWN, null)
        }
        val lastOther = states.indexOfLast { it != current }
        // 직전 상태가 UNKNOWN 이면 교차가 아니라 200봉이 처음 찬 날이라 전환으로 보지 않는다
        val since = if (lastOther >= 0 && states[lastOther] != TrendStateEnum.UNKNOWN) {
            candles[lastOther + 1].date
        } else {
            null
        }
        return TrendStatus(current, since)
    }
}
