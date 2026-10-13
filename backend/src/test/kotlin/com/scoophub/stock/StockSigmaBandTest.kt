package com.scoophub.stock

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** 주간 σ 밴드 — 앵커 선택, 적용 구간, 위치·상태 (#260) */
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
}
