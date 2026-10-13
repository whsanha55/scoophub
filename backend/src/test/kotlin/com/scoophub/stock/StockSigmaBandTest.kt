package com.scoophub.stock

import com.scoophub.stock.vo.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** 주간 σ 밴드 — 앵커 선택, 적용 구간, 위치·상태 (#260), 주간 마감 후 수익률 (#262) */
class StockSigmaBandTest {

    private fun d(s: String) = LocalDate.parse(s)

    private fun snap(snapshot: String, expiry: String, price: Double = 100.0, move: Double = 5.0) =
        SigmaBand(snapshotDate = d(snapshot), expiryDate = d(expiry), anchorPrice = price, expectedMove = move)

    @Test
    fun `같은 만기의 스냅샷 중 가장 이른 것이 그 주의 앵커가 된다`() {
        // given — 금(10-02) 스냅샷이 다음 주 금(10-09) 만기를 열고, 월~목 스냅샷은 같은 만기를 다시 잰다
        val snapshots = listOf(
            snap("2026-10-02", "2026-10-09", price = 100.0),
            snap("2026-10-05", "2026-10-09", price = 103.0),
            snap("2026-10-06", "2026-10-09", price = 104.0),
            snap("2026-10-09", "2026-10-16", price = 110.0),
        )

        // when
        val bands = StockSigmaBand.weeklyBands(snapshots)

        // then
        assertThat(bands.map { it.snapshotDate to it.anchorPrice }).containsExactly(
            d("2026-10-02") to 100.0,
            d("2026-10-09") to 110.0,
        )
    }

    @Test
    fun `한 스냅샷 날짜에 만기가 여러 개면 가장 가까운 만기만 쓴다`() {
        // given — #250 이전 데이터처럼 같은 날 월물 만기가 함께 저장된 경우
        val snapshots = listOf(
            snap("2026-10-02", "2026-10-09", move = 5.0),
            snap("2026-10-02", "2026-11-20", move = 20.0),
        )

        // when
        val bands = StockSigmaBand.weeklyBands(snapshots)

        // then
        assertThat(bands).containsExactly(snap("2026-10-02", "2026-10-09", move = 5.0))
    }

    @Test
    fun `expected move 가 0 이하인 스냅샷은 앵커로 쓰지 않는다`() {
        // when
        val bands = StockSigmaBand.weeklyBands(listOf(snap("2026-10-02", "2026-10-09", move = 0.0)))

        // then
        assertThat(bands).isEmpty()
    }

    @Test
    fun `밴드는 앵커 스냅샷 다음 날부터 만기일까지 적용된다`() {
        // given
        val bands = StockSigmaBand.weeklyBands(
            listOf(snap("2026-10-02", "2026-10-09"), snap("2026-10-09", "2026-10-16", price = 110.0)),
        )

        // when & then — 앵커 당일은 그날 종가가 앵커라 제외, 만기일(주간 마감)은 포함
        assertThat(StockSigmaBand.bandOn(bands, d("2026-10-02"))).isNull()
        assertThat(StockSigmaBand.bandOn(bands, d("2026-10-05"))?.snapshotDate).isEqualTo(d("2026-10-02"))
        assertThat(StockSigmaBand.bandOn(bands, d("2026-10-09"))?.snapshotDate).isEqualTo(d("2026-10-02"))
        assertThat(StockSigmaBand.bandOn(bands, d("2026-10-12"))?.anchorPrice).isEqualTo(110.0)
        assertThat(StockSigmaBand.bandOn(bands, d("2026-10-19"))).isNull()
    }

    @Test
    fun `σ 위치는 앵커 대비 변화를 expected move 로 나눈 값이다`() {
        // given
        val band = snap("2026-10-02", "2026-10-09", price = 100.0, move = 4.0)

        // when & then
        assertThat(band.position(97.76)).isCloseTo(-0.56, within(1e-9))
        assertThat(band.upper).isEqualTo(104.0)
        assertThat(band.lower).isEqualTo(96.0)
    }

    @Test
    fun `σ 위치가 1 이상이면 상단 돌파, -1 이하면 하단 이탈, 그 사이는 정상이다`() {
        assertThat(StockSigmaBand.state(1.0)).isEqualTo(SigmaStateEnum.UPPER_BREAK)
        assertThat(StockSigmaBand.state(0.99)).isEqualTo(SigmaStateEnum.NORMAL)
        assertThat(StockSigmaBand.state(-0.99)).isEqualTo(SigmaStateEnum.NORMAL)
        assertThat(StockSigmaBand.state(-1.0)).isEqualTo(SigmaStateEnum.LOWER_BREAK)
    }

    private fun candles(from: String, closes: List<Double>, skip: Set<String> = emptySet()) = closes
        .mapIndexed { i, close -> Candle("QQQ", "1D", d(from).plusDays(i.toLong()), close, close, close, close, 0.0) }
        .filter { it.date.toString() !in skip }

    @Test
    fun `주간 마감 종가의 σ 위치와 이후 5·10·20 거래일 수익률을 계산한다`() {
        // given — 10-01 부터 매일 1씩 오르는 종가. 10-02 앵커(100 ± 4) → 10-09 마감 108
        val history = StockSigmaBand.weeklyHistory(
            listOf(snap("2026-10-02", "2026-10-09", price = 100.0, move = 4.0)),
            candles("2026-10-01", (0 until 40).map { 100.0 + it }),
        )

        // when
        val week = history.single()

        // then
        assertThat(week.closeDate).isEqualTo(d("2026-10-09"))
        assertThat(week.close).isEqualTo(108.0)
        assertThat(week.position).isCloseTo(2.0, within(1e-9))
        assertThat(week.state).isEqualTo(SigmaStateEnum.UPPER_BREAK)
        assertThat(week.return1w).isCloseTo(5.0 / 108 * 100, within(1e-9))
        assertThat(week.return2w).isCloseTo(10.0 / 108 * 100, within(1e-9))
        assertThat(week.return4w).isCloseTo(20.0 / 108 * 100, within(1e-9))
    }

    @Test
    fun `이후 일봉이 모자라면 수익률은 비우고 아직 마감 전인 주는 뺀다`() {
        // given — 마지막 일봉 10-16. 10-09 마감 주는 +1W 만 있고, 10-23 마감 주는 아직 진행 중
        val history = StockSigmaBand.weeklyHistory(
            listOf(snap("2026-10-02", "2026-10-09"), snap("2026-10-16", "2026-10-23")),
            candles("2026-10-01", (0 until 16).map { 100.0 + it }),
        )

        // when & then
        assertThat(history.map { it.band.expiryDate }).containsExactly(d("2026-10-09"))
        assertThat(history.single().return1w).isNotNull()
        assertThat(history.single().return2w).isNull()
        assertThat(history.single().return4w).isNull()
    }

    @Test
    fun `만기일이 휴장이면 만기 전 마지막 일봉을 마감으로 쓰고 최근 주가 먼저 온다`() {
        // given — 10-09 휴장
        val history = StockSigmaBand.weeklyHistory(
            listOf(snap("2026-10-02", "2026-10-09"), snap("2026-10-09", "2026-10-16", price = 110.0)),
            candles("2026-10-01", (0 until 30).map { 100.0 + it }, skip = setOf("2026-10-09")),
        )

        // when & then
        assertThat(history.map { it.closeDate }).containsExactly(d("2026-10-16"), d("2026-10-08"))
    }
}
