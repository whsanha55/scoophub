package com.scoophub.news

import com.scoophub.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import javax.sql.DataSource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RssNewsRemovalTest @Autowired constructor(private val jdbc: JdbcClient, private val dataSource: DataSource) {
    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM notify_routes WHERE category = 'news'").update()
        // V31 에서 삭제된 테이블 — V22 실행 시점의 스키마를 재현한다
        jdbc.sql("CREATE TABLE IF NOT EXISTS crawl_sources (crawler TEXT, name TEXT, url TEXT)").update()
    }

    @AfterEach
    fun dropLegacyTables() {
        jdbc.sql("DROP TABLE IF EXISTS crawl_sources").update()
    }

    private fun migrate() {
        ResourceDatabasePopulator(ClassPathResource("db/migration/V22__remove_rss_news.sql")).execute(dataSource)
    }

    private fun route(purpose: String): Long = jdbc.sql(
        """
        INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name)
        VALUES ('news', :purpose, 'telegram', 'existing-chat', 77, '기존 뉴스') RETURNING id
        """.trimIndent(),
    ).param("purpose", purpose).query(Long::class.java).single()

    private fun record(routeId: Long, status: String) {
        jdbc.sql("INSERT INTO notify_log (route_id, payload_key, status) VALUES (:id, 'news:alpaca:777', :status)")
            .param("id", routeId).param("status", status).update()
    }

    @Test
    fun `RSS 뉴스 테이블과 설정을 없애고 기술 뉴스레터 RSS는 유지한다`() {
        // given — emulate an existing deployment before the replacement migration.
        jdbc.sql("CREATE TABLE feed_news (id BIGINT PRIMARY KEY)").update()
        jdbc.sql(
            "INSERT INTO crawl_sources (crawler, name, url) VALUES ('news', 'old feed', 'https://example.com/rss')",
        ).update()
        jdbc.sql("INSERT INTO crawl_config (crawler) VALUES ('news')").update()
        jdbc.sql(
            "INSERT INTO crawl_schedule (crawler, job_id, schedule_type, schedule_minutes) VALUES ('news', 'old-news-job', 'interval', 15)",
        ).update()
        // when
        migrate()
        // then
        assertThat(jdbc.sql("SELECT to_regclass('feed_news') IS NULL").query(Boolean::class.java).single()).isTrue()
        for (table in listOf("crawl_sources", "crawl_config", "crawl_schedule")) {
            assertThat(
                jdbc.sql("SELECT COUNT(*) FROM $table WHERE crawler = 'news'").query(Int::class.java).single(),
            ).isZero()
        }
        assertThat(
            jdbc.sql(
                "SELECT COUNT(*) FROM crawl_config WHERE crawler = 'tech_newsletter'",
            ).query(Int::class.java).single(),
        ).isEqualTo(1)
        assertThat(
            jdbc.sql(
                "SELECT COUNT(*) FROM crawl_schedule WHERE crawler = 'tech_newsletter'",
            ).query(Int::class.java).single(),
        ).isEqualTo(1)
    }

    @Test
    fun `기존 뉴스 라우트는 토픽과 발송 이력을 유지하면서 alpaca로 바뀐다`() {
        // given
        val id = route("rss")
        record(id, "success")
        // when
        migrate()
        // then
        val result = jdbc.sql("SELECT purpose, topic_id, chat_id FROM notify_routes WHERE id = :id")
            .param("id", id).query { rs, _ -> Triple(rs.getString(1), rs.getLong(2), rs.getString(3)) }.single()
        assertThat(result).isEqualTo(Triple("alpaca", 77L, "existing-chat"))
        assertThat(
            jdbc.sql(
                "SELECT status FROM notify_log WHERE route_id = :id",
            ).param("id", id).query(String::class.java).single(),
        ).isEqualTo("success")
    }

    @Test
    fun `이미 alpaca 라우트가 있으면 충돌 없이 성공 발송 이력을 합친다`() {
        // given
        val old = route("rss")
        val current = route("alpaca")
        record(old, "success")
        record(current, "error")
        // when
        migrate()
        // then
        assertThat(
            jdbc.sql(
                "SELECT COUNT(*) FROM notify_routes WHERE category = 'news' AND purpose = 'rss'",
            ).query(Int::class.java).single(),
        ).isZero()
        assertThat(
            jdbc.sql(
                "SELECT status FROM notify_log WHERE route_id = :id",
            ).param("id", current).query(String::class.java).single(),
        ).isEqualTo("success")
        assertThat(
            jdbc.sql(
                "SELECT COUNT(*) FROM notify_log WHERE route_id = :id",
            ).param("id", current).query(Int::class.java).single(),
        ).isEqualTo(1)
        migrate()
        assertThat(
            jdbc.sql("SELECT COUNT(*) FROM notify_routes WHERE category = 'news'").query(Int::class.java).single(),
        ).isEqualTo(1)
    }
}
