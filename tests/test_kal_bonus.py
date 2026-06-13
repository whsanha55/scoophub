# tests/test_kal_bonus.py
from datetime import datetime, timezone

import pytest

from app.kal_bonus.cabin import CABIN_LABELS, map_cabin
from app.kal_bonus.config import ROUTES, TARGET_MONTHS, make_key
from app.kal_bonus.kal_bonus_scraper import KalBonusScraper, parse_bonus_response
from app.crawl_data.repo import CrawlDataRepo

SAMPLE_RAW = {
    "departureAirport": "ICN",
    "arrivalAirport": "LHR",
    "flightList": [
        {
            "departureDate": "20270101",
            "flightDetailList": [
                {"departureTime": "10:40", "flightNumber": "KE907",
                 "availableSeat": True, "bookingClass": "X", "frontBookingClass": "E"},
                {"departureTime": "10:40", "flightNumber": "KE907",
                 "availableSeat": False, "bookingClass": "A", "frontBookingClass": "F"},
            ],
        },
    ],
}


def test_cabin_mapping_known_codes():
    assert CABIN_LABELS["E"] == "일반석 보너스"
    assert CABIN_LABELS["P"] == "프레스티지석 보너스"
    assert CABIN_LABELS["F"] == "일등석 보너스/좌석승급"
    assert map_cabin("E") == "일반석 보너스"
    assert map_cabin("Ø") == "운항편 없음"


def test_cabin_mapping_unknown_passthrough():
    assert map_cabin("Z") == "Z"
    assert map_cabin(None) == ""
    assert map_cabin("") == ""


def test_parse_bonus_response_maps_cabin():
    parsed = parse_bonus_response(SAMPLE_RAW)
    assert parsed["departure"] == "ICN"
    assert parsed["arrival"] == "LHR"
    flights = parsed["days"][0]["flights"]
    assert flights[0]["front_booking_class"] == "E"
    assert flights[0]["cabin_label"] == "일반석 보너스"
    assert flights[0]["available"] is True
    assert flights[1]["cabin_label"] == "일등석 보너스/좌석승급"
    assert flights[1]["available"] is False


def test_parse_bonus_response_empty():
    assert parse_bonus_response({})["days"] == []
    assert parse_bonus_response({"flightList": None})["days"] == []


def test_config_scope():
    # 10노선 × 3월 = 30 호출/일
    assert len(ROUTES) == 10
    assert len(TARGET_MONTHS) == 3
    assert len(ROUTES) * len(TARGET_MONTHS) == 30
    assert make_key("ICN", "LHR", "202701") == "202701-ICN-LHR"


@pytest.fixture(autouse=True)
async def _clean(db):
    pool = await db.pool
    async with pool.acquire() as conn:
        await conn.execute("TRUNCATE crawl_data RESTART IDENTITY CASCADE")
    yield
    async with pool.acquire() as conn:
        await conn.execute("TRUNCATE crawl_data RESTART IDENTITY CASCADE")


async def test_store_via_crawl_data(db, monkeypatch):
    """scraper가 crawl_data(category=kal, purpose=bonus_seat)에 적재하는지 통합 검증.

    _crawl_all을 샘플 응답으로 치환 — Playwright 라이브 크롤은 환경 의존(제외).
    """
    scraper = KalBonusScraper(db)

    async def fake_crawl(targets):
        return [SAMPLE_RAW if (arr, ym) == ("LHR", "202701") else None
                for _dep, arr, ym in targets]

    monkeypatch.setattr(scraper, "_crawl_all", fake_crawl)

    counts = await scraper.fetch_and_store()
    assert counts["stored"] == 1

    row = await CrawlDataRepo(db).get(
        category="kal", purpose="bonus_seat", key="202701-ICN-LHR"
    )
    assert row is not None
    assert row["response"]["arrivalAirport"] == "LHR"
    assert isinstance(row["date_at"], datetime)
