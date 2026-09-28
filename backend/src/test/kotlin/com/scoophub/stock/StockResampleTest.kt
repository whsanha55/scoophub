package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * legacy tests/test_stock_resample.py 포팅 — 1D → 주/월 resample 정합성.
 *
 * 규칙: open=first, high=max, low=min, close=last, volume=sum.
 * 경계: 주간=월요일 시작(W-MON), 월간=자연월.
 * 최소 캔들 수 미달 시 빈 리스트(가짜 분석 영속화 방지).
 */
class StockResampleTest {

    private fun candle(d: LocalDate, o: Double, h: Double, l: Double, c: Double, v: Double) = Candle(
        ticker = "TST",
        interval = "1D",
        date = d,
        open = o,
        high = h,
        low = l,
        close = c,
        volume = v,
    )

    @Test
    fun `주봉 집계가 open first close last high max low min volume sum 규칙을 따른다`() {
        // given — 2024-01-01(월)부터 40주 분량 평일(월-금), 매일 변동하는 OHLCV
        val base = LocalDate.of(2024, 1, 1)
        val daily = buildList {
            for (week in 0 until 40) {
                for (day in 0 until 5) {
                    add(
                        candle(
                            base.plusDays((week * 7 + day).toLong()),
                            100.0 + day,
                            105.0 + day,
                            95.0 + day,
                            102.0 + day,
                            1000.0 + day,
                        ),
                    )
                }
            }
        }

        // when
        val weekly = StockResample.weekly(daily)

        // then
        assertThat(weekly).hasSize(40)
        val first = weekly.first()
        assertThat(first.date).isEqualTo(LocalDate.of(2024, 1, 1))
        assertThat(first.open).isEqualTo(100.0) // 월요일 open
        assertThat(first.close).isEqualTo(106.0) // 금요일 close (102+4)
        assertThat(first.high).isEqualTo(109.0) // 금요일 high (105+4)
        assertThat(first.low).isEqualTo(95.0) // 월요일 low
        assertThat(first.volume).isEqualTo((1000 + 1001 + 1002 + 1003 + 1004).toDouble())
        assertThat(first.interval).isEqualTo("1W")
    }

    @Test
    fun `주봉 리샘플이 월요일 경계로 주를 나눈다`() {
        // given — 30주 × 월~일 7일 전체: 토/일은 그대로 그 주(월요일 시작)에 속하고 다음 월요일은 새 버킷
        val base = LocalDate.of(2024, 1, 1)
        val daily = buildList {
            for (week in 0 until 30) {
                for (day in 0 until 7) {
                    add(candle(base.plusDays((week * 7 + day).toLong()), 100.0, 101.0, 99.0, 100.0, 10.0))
                }
            }
        }

        // when
        val weekly = StockResample.weekly(daily)

        // then
        assertThat(weekly).hasSize(30)
        assertThat(weekly[0].date).isEqualTo(LocalDate.of(2024, 1, 1))
        assertThat(weekly[1].date).isEqualTo(LocalDate.of(2024, 1, 8)) // 다음 주 월요일
    }

    @Test
    fun `주봉 캔들이 최소 26개 미만이면 빈 리스트를 반환한다`() {
        // given — 5주치 평일 → 주봉 5개(< 26)
        val base = LocalDate.of(2024, 1, 1)
        val daily = buildList {
            for (week in 0 until 5) {
                for (day in 0 until 5) {
                    add(candle(base.plusDays((week * 7 + day).toLong()), 100.0, 101.0, 99.0, 100.0, 10.0))
                }
            }
        }

        // when & then — 분석 스킵
        assertThat(StockResample.weekly(daily)).isEmpty()
    }

    @Test
    fun `주봉 리샘플에 빈 입력을 넣으면 빈 리스트를 반환한다`() {
        assertThat(StockResample.weekly(emptyList())).isEmpty()
    }

    @Test
    fun `월봉 집계가 자연월 단위로 이루어진다`() {
        // given — 24개월 × 각 월 1~28일, open/close 모두 day에 비례해 변동
        val daily = buildList {
            for (monthOffset in 0 until 24) {
                val year = 2024 + monthOffset / 12
                val month = monthOffset % 12 + 1
                for (day in 1..28) {
                    add(
                        candle(
                            LocalDate.of(year, month, day),
                            (200 + day).toDouble(),
                            210.0,
                            190.0,
                            (200 + day).toDouble(),
                            50.0,
                        ),
                    )
                }
            }
        }

        // when
        val monthly = StockResample.monthly(daily)

        // then
        assertThat(monthly).hasSize(24)
        val first = monthly.first()
        assertThat(first.date).isEqualTo(LocalDate.of(2024, 1, 1))
        assertThat(first.open).isEqualTo(201.0) // 1일 open (200+1)
        assertThat(first.close).isEqualTo(228.0) // 28일 close (200+28)
        assertThat(first.high).isEqualTo(210.0)
        assertThat(first.low).isEqualTo(190.0)
        assertThat(first.volume).isEqualTo(50.0 * 28)
        assertThat(first.interval).isEqualTo("1M")
    }

    @Test
    fun `월봉 리샘플이 자연월 경계로 월을 나눈다`() {
        // given — 1월 31일과 2월 1일이 서로 다른 월봉에 속하는지
        val daily = buildList {
            for (monthOffset in 0 until 22) {
                val year = 2024 + monthOffset / 12
                val month = monthOffset % 12 + 1
                for (day in 1..28) {
                    add(candle(LocalDate.of(year, month, day), 100.0, 101.0, 99.0, 100.0, 10.0))
                }
            }
        }

        // when
        val monthly = StockResample.monthly(daily)

        // then
        assertThat(monthly[0].date).isEqualTo(LocalDate.of(2024, 1, 1))
        assertThat(monthly[1].date).isEqualTo(LocalDate.of(2024, 2, 1))
    }

    @Test
    fun `월봉 캔들이 최소 20개 미만이면 빈 리스트를 반환한다`() {
        // given — 2024-01 한 달치뿐 → 월봉 1개(< 20)
        val daily = (1..28).map { day ->
            candle(LocalDate.of(2024, 1, day), 100.0, 101.0, 99.0, 100.0, 10.0)
        }

        // when & then
        assertThat(StockResample.monthly(daily)).isEmpty()
    }
}
