package com.scoophub.hotdeal

import com.scoophub.hotdeal.vo.HotdealNotifySettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

class HotdealNotifySettingsTest {
    private val mapper = JsonMapper.builder().build()

    private fun settings(json: String) = HotdealNotifySettings.from(mapper.readTree(json))

    @Test
    fun `자정을 넘기는 조용한 시간은 밤과 새벽 모두 조용하다`() {
        // given — KST 23:00~08:00
        val s = settings("""{"quiet_start": "23:00", "quiet_end": "08:00", "timezone": "Asia/Seoul"}""")

        // when / then (UTC+9)
        assertThat(s.isQuiet(Instant.parse("2026-10-14T14:30:00Z"))).isTrue() // 23:30
        assertThat(s.isQuiet(Instant.parse("2026-10-13T22:59:00Z"))).isTrue() // 07:59
        assertThat(s.isQuiet(Instant.parse("2026-10-13T23:00:00Z"))).isFalse() // 08:00
        assertThat(s.isQuiet(Instant.parse("2026-10-14T03:00:00Z"))).isFalse() // 12:00
    }

    @Test
    fun `하루 상한은 설정 시간대의 0시부터 센다`() {
        // given
        val s = settings("""{"timezone": "Asia/Seoul"}""")

        // when
        val dayStart = s.dayStart(Instant.parse("2026-10-14T03:00:00Z"))

        // then — KST 2026-10-14 00:00
        assertThat(dayStart).isEqualTo(Instant.parse("2026-10-13T15:00:00Z"))
    }

    @Test
    fun `값이 없거나 잘못되면 기본값을 쓴다`() {
        // when
        val s = settings("""{"quiet_start": "25:99", "timezone": "Nowhere/City"}""")

        // then
        assertThat(s.enabled).isFalse()
        assertThat(s.scoreThreshold).isEqualTo(5.0)
        assertThat(s.dailyLimit).isEqualTo(5)
        assertThat(s.quietStart.toString()).isEqualTo("23:00")
        assertThat(s.zone.id).isEqualTo("Asia/Seoul")
        assertThat(s.maxAge).isEqualTo(Duration.ofHours(6))
    }
}
