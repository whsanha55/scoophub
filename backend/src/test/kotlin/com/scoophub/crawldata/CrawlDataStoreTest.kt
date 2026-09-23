package com.scoophub.crawldata

import com.scoophub.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** legacy tests/test_crawl_data.py 포팅 — JSONB(Jackson 3 JsonNode) 매핑 검증 포함 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CrawlDataStoreTest @Autowired constructor(
    private val store: CrawlDataStore,
    private val repository: CrawlDataRepository,
) {
    @BeforeEach
    fun clean() = repository.deleteAllInBatch()

    @Test
    fun `upsert 는 새 row 를 넣고 JSONB 응답을 그대로 읽는다`() {
        val id = store.upsert(
            "kal", "bonus_seat", "ICN-LHR-202701",
            mapOf("flightList" to listOf(mapOf("departureDate" to "20270101"))),
        )

        val row = assertNotNull(repository.findByCategoryAndPurposeAndKey("kal", "bonus_seat", "ICN-LHR-202701"))
        assertEquals(id, row.id)
        assertEquals("20270101", row.response["flightList"][0]["departureDate"].asString())
    }

    @Test
    fun `같은 key 는 같은 row 에 최신 응답으로 덮어쓴다`() {
        val first = store.upsert("kal", "bonus_seat", "ICN-LHR-202701", mapOf("v" to 1))
        val later = Instant.parse("2027-01-02T09:00:00Z")
        val updated = store.upsert("kal", "bonus_seat", "ICN-LHR-202701", mapOf("v" to 2), later)

        assertEquals(first, updated)
        val row = assertNotNull(repository.findByCategoryAndPurposeAndKey("kal", "bonus_seat", "ICN-LHR-202701"))
        assertEquals("""{"v":2}""", row.response.toString())
        assertEquals(later, row.dateAt)
    }

    @Test
    fun `latest 는 date_at 이 가장 최근인 1건`() {
        store.upsert("kal", "bonus_seat", "ICN-LHR-202701", mapOf("k" to "old"), Instant.parse("2027-01-01T00:00:00Z"))
        store.upsert("kal", "bonus_seat", "ICN-FRA-202701", mapOf("k" to "new"), Instant.parse("2027-01-05T00:00:00Z"))

        val latest = assertNotNull(repository.findFirstByCategoryAndPurposeOrderByDateAtDesc("kal", "bonus_seat"))
        assertEquals("ICN-FRA-202701", latest.key)
        assertEquals("new", latest.response["k"].asString())
    }

    @Test
    fun `JSONB 경로 값으로 필터`() {
        store.upsert("kal", "bonus_seat", "ICN-LHR-202701", mapOf("meta" to mapOf("status" to "open")))
        store.upsert("kal", "bonus_seat", "ICN-CDG-202701", mapOf("meta" to mapOf("status" to "closed")))

        val hits = repository.findByPath("kal", "bonus_seat", "meta.status", "open")
        assertEquals(listOf("ICN-LHR-202701"), hits.map { it.key })
    }

    @Test
    fun `data class 는 전역 snake_case 로 저장된다`() {
        data class Snapshot(val tempC: Int, val feelsLike: Int)
        store.upsert("weather", "snapshot", "seoul", Snapshot(20, 18))

        val row = assertNotNull(repository.findByCategoryAndPurposeAndKey("weather", "snapshot", "seoul"))
        assertEquals("""{"temp_c":20,"feels_like":18}""", row.response.toString())
    }
}
