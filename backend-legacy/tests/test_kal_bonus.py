# tests/test_kal_bonus.py
from datetime import datetime, timezone

import pytest

from app.kal_bonus.cabin import CABIN_LABELS, map_cabin
from app.kal_bonus.config import ROUTES, TARGET_MONTHS, generate_target_months, load_routes_config, make_key
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
    # TARGET_MONTHS 는 오늘(UTC) 기준 13개월 자동생성.
    now = datetime.now(timezone.utc)
    assert len(ROUTES) == 10
    assert len(TARGET_MONTHS) == 13
    assert TARGET_MONTHS == generate_target_months(13)
    assert TARGET_MONTHS[0] == f"{now.year:04d}{now.month:02d}"
    assert make_key("ICN", "LHR", "202701") == "202701-ICN-LHR"


async def test_scraper_uses_custom_routes_months():
    """scraper가 전달받은 routes/months로 targets를 2중 루프 생성하는지."""
    captured = {}
    scraper = KalBonusScraper(
        db=None, departure="ICN",
        routes=[("LHR", "런던"), ("CDG", "파리")], months=["202701"],
    )

    async def fake_crawl(targets):
        captured["targets"] = targets
        return [None] * len(targets)  # 모두 None → upsert 미호출

    scraper._crawl_all = fake_crawl
    counts = await scraper.fetch_and_store()
    assert captured["targets"] == [("ICN", "LHR", "202701"), ("ICN", "CDG", "202701")]
    assert counts == {"targets": 2, "stored": 0}


async def test_load_routes_config_fallback(db):
    """crawl_sources row 없으면 폴백 기본값(10노선 × 자동생성 13개월)."""
    pool = await db.pool
    async with pool.acquire() as conn:
        await conn.execute("DELETE FROM crawl_sources WHERE crawler='kal_bonus'")
    dep, routes, months = await load_routes_config(db)
    assert dep == "ICN"
    assert len(routes) == 10
    assert months == generate_target_months(13)


async def test_load_routes_config_from_db(db):
    """crawl_sources config JSONB에서 노선/기간 로드."""
    pool = await db.pool
    cfg = '{"departure":"ICN","routes":[{"arrival":"LHR","city":"런던"},{"arrival":"CDG","city":"파리"}],"months":["202701"]}'
    async with pool.acquire() as conn:
        await conn.execute("DELETE FROM crawl_sources WHERE crawler='kal_bonus'")
        await conn.execute(
            "INSERT INTO crawl_sources(crawler,name,url,active,config) "
            "VALUES('kal_bonus','t','u',TRUE,$1::jsonb)", cfg,
        )
    dep, routes, months = await load_routes_config(db)
    assert dep == "ICN"
    assert routes == [("LHR", "런던"), ("CDG", "파리")]
    assert months == ["202701"]


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
