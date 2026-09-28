package com.scoophub.global.schedule

import com.scoophub.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** legacy tests/test_scheduler.py 의 resolve_trigger/resolve_params 단위 포팅 — DB seed(V11/V12) 필요 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ScheduleResolverTest @Autowired constructor(private val resolver: ScheduleResolver) {
    @Test
    fun `cron 행을 해석한다`() {
        // when
        val resolved = resolver.resolveTrigger("github_trending", "github_trending_crawler")

        // then
        assertThat(resolved.trigger).isEqualTo(ScheduleTrigger.Cron(listOf("0 9 * * *")))
        assertThat(resolved.enabled).isTrue()
    }

    @Test
    fun `interval 행을 해석한다`() {
        // when
        val resolved = resolver.resolveTrigger("news", "news_crawler")

        // then
        assertThat(resolved.trigger).isEqualTo(ScheduleTrigger.Interval(15))
        assertThat(resolved.enabled).isTrue()
    }

    @Test
    fun `행이 없으면 예외`() {
        assertThatThrownBy { resolver.resolveTrigger("nonexistent", "nope") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `params 는 crawl_config 에서 읽는다`() {
        assertThat(resolver.resolveParams("github_trending")["max_repos"].asInt()).isEqualTo(25)
    }

    @Test
    fun `crawl_config 행이 없으면 빈 객체`() {
        // weather — #127 이관 대상 아님, params 불필요
        assertThat(resolver.resolveParams("weather").toString()).isEqualTo("{}")
    }
}
