package com.scoophub.system

import com.scoophub.TestcontainersConfiguration
import com.scoophub.system.service.DataRetentionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DataRetentionTest @Autowired constructor(
    private val service: DataRetentionService,
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) {
    private val old get() = Timestamp.from(clock.instant().minus(Duration.ofDays(100)))
    private val recent get() = Timestamp.from(clock.instant().minus(Duration.ofDays(10)))

    /** 다른 테스트 클래스가 같은 DB 를 쓰므로 남긴 pending 기사 등을 정리한다 */
    @BeforeEach
    @AfterEach
    fun clean() {
        listOf("crawl_logs", "notify_log", "news_article").forEach { jdbcClient.sql("DELETE FROM $it").update() }
        jdbcClient.sql("DELETE FROM notify_routes WHERE category = 'retention-test'").update()
    }

    @Test
    fun `90일 지난 로그와 처리 완료 기사만 삭제한다`() {
        // given
        listOf(old, recent).forEach { at ->
            jdbcClient.sql(
                "INSERT INTO crawl_logs (crawler, status, started_at) VALUES ('weather', 'success', :at)",
            ).param("at", at).update()
        }
        val routeId = jdbcClient.sql(
            "INSERT INTO notify_routes (category, purpose, chat_id) VALUES ('retention-test', '', '1') RETURNING id",
        ).query(Long::class.java).single()
        listOf("old" to old, "recent" to recent).forEach { (key, at) ->
            jdbcClient.sql(
                "INSERT INTO notify_log (route_id, payload_key, status, sent_at) VALUES (:route, :key, 'success', :at)",
            ).param("route", routeId).param("key", key).param("at", at).update()
        }
        listOf(Triple(1L, old, "pushed"), Triple(2L, old, "pending"), Triple(3L, recent, "skipped")).forEach {
            val (id, at, status) = it
            jdbcClient.sql(
                """
                INSERT INTO news_article (id, source, headline, published_at, source_updated_at, status)
                VALUES (:id, 'benzinga', 'h', :at, :at, :status)
                """,
            ).param("id", id).param("at", at).param("status", status).update()
        }

        // when
        service.purge()

        // then
        assertThat(count("SELECT COUNT(*) FROM crawl_logs")).isEqualTo(1)
        assertThat(
            jdbcClient.sql("SELECT payload_key FROM notify_log").query(String::class.java).list(),
        ).containsExactly("recent")
        assertThat(
            jdbcClient.sql("SELECT id FROM news_article ORDER BY id").query(Long::class.java).list(),
        ).containsExactly(2L, 3L)
    }

    private fun count(sql: String) = jdbcClient.sql(sql).query(Int::class.java).single()
}
