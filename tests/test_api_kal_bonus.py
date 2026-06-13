# tests/test_api_kal_bonus.py
"""KAL bonus seat API 통합 테스트 — scheduler/crawl trigger + 조회 엔드포인트."""
import json
from datetime import datetime, timezone


SAMPLE = {
    "departureAirport": "ICN",
    "arrivalAirport": "LHR",
    "departureAirportName": "서울/인천",
    "arrivalAirportName": "런던/히스로",
    "flightList": [
        {"departureDate": "20270101", "flightDetailList": [
            {"departureTime": "10:40", "flightNumber": "KE907",
             "availableSeat": True, "bookingClass": "X", "frontBookingClass": "E"},
        ]},
    ],
}


async def _seed(db, key, response, when):
    pool = await db.pool
    async with pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO crawl_data (category, purpose, key, date_at, response) "
            "VALUES ('kal','bonus_seat',$1,$2,$3::jsonb)",
            key, when, json.dumps(response),
        )


async def test_get_kal_bonus_returns_seeded(client, db):
    await _seed(db, "ICN-LHR-202701", SAMPLE,
                datetime(2027, 1, 1, 7, 0, tzinfo=timezone.utc))
    res = await client.get("/api/kal-bonus?arrival=LHR")
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["meta"]["returned"] == 1
    item = body["data"][0]
    assert item["key"] == "ICN-LHR-202701"
    assert item["response"]["arrivalAirport"] == "LHR"
    # parsed 매핑 검증
    assert item["parsed"]["days"][0]["flights"][0]["cabin_label"] == "일반석 보너스"


async def test_get_kal_bonus_empty(client, db):
    res = await client.get("/api/kal-bonus?arrival=FRA")
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"] == []
    assert body["meta"]["total"] == 0


async def test_get_kal_bonus_filter_by_arrival(client, db):
    await _seed(db, "ICN-LHR-202701", SAMPLE,
                datetime(2027, 1, 1, tzinfo=timezone.utc))
    other = {**SAMPLE, "arrivalAirport": "CDG"}
    await _seed(db, "ICN-CDG-202701", other,
                datetime(2027, 1, 2, tzinfo=timezone.utc))
    res = await client.get("/api/kal-bonus?arrival=CDG")
    body = res.json()
    assert body["meta"]["returned"] == 1
    assert body["data"][0]["key"] == "ICN-CDG-202701"


async def test_crawl_trigger_plumbing(client, db, monkeypatch):
    """POST /api/crawling/kal-bonus 가 crawler를 로드·실행하는지 (크롤 자체는 치환)."""
    from app.kal_bonus import crawler as crawler_mod

    async def fake_fetch(self):
        from app.core.base_crawler import CrawlResult
        return CrawlResult(items_fetched=30, items_new=1)

    monkeypatch.setattr(crawler_mod.KalBonusCrawler, "fetch", fake_fetch)

    res = await client.post("/api/crawling/kal-bonus")
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["items_new"] == 1
    assert body["data"]["crawler"] == "kal_bonus"
