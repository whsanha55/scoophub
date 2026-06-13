# app/kal_bonus/config.py
"""대한항공 보너스 좌석 크롤 대상 config.

대상(출발/도착 노선/기간)은 DB crawl_sources(crawler='kal_bonus')의
config JSONB에서 읽는다. 아래 상수는 폴백 기본값(첫 실행 / row 없을 때).
신규 노선 추가 = crawl_sources row 갱신, 코드 수정 불필요.
"""
from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

# KAL 보너스 좌석 조회 API (비로그인 POST)
ENDPOINT = "https://www.koreanair.com/api/hmp/bonusSeatView/bonusSeatView"

# crawl_sources.crawler 값
CRAWLER_KEY = "kal_bonus"

# 폴백 기본값
DEPARTURE = "ICN"

# 도착 유럽 10노선: (공항코드, 도시명)
ROUTES: list[tuple[str, str]] = [
    ("LHR", "런던/히스로"),
    ("FCO", "로마/레오나르도 다빈치"),
    ("LIS", "리스본"),
    ("MAD", "마드리드"),
    ("MXP", "밀라노/말펜사"),
    ("AMS", "암스테르담/스키폴"),
    ("IST", "이스탄불"),
    ("ZRH", "취리히"),
    ("CDG", "파리/샤를 드 골"),
    ("FRA", "프랑크푸르트"),
]

# 2027 Q1 (월 첫날 YYYYMMDD → 그 달 전체 응답)
TARGET_MONTHS: list[str] = ["202701", "202702", "202703"]

# crawl_data 자연키(category/purpose 고정)
CATEGORY = "kal"
PURPOSE = "bonus_seat"


async def load_routes_config(
    db: "Database",
) -> tuple[str, list[tuple[str, str]], list[str]]:
    """crawl_sources에서 활성 KAL 대상 설정 1건 로드.

    config JSONB 스키마:
        {"departure": "ICN",
         "routes":   [{"arrival": "LHR", "city": "런던/히스로"}, ...],
         "months":   ["202701", "202702", "202703"]}

    활성 row가 없거나 필드 누락 시 모듈 폴백 기본값 사용.
    반환: (departure, [(arrival, city), ...], [year_month, ...])
    """
    row = await db.fetchrow(
        "SELECT config FROM crawl_sources "
        "WHERE crawler = $1 AND active = TRUE "
        "ORDER BY updated_at DESC LIMIT 1",
        CRAWLER_KEY,
    )
    cfg = row["config"] if row else None
    if isinstance(cfg, str):
        import json
        cfg = json.loads(cfg)

    if not cfg:
        return DEPARTURE, list(ROUTES), list(TARGET_MONTHS)

    departure = cfg.get("departure") or DEPARTURE
    routes = [
        (r["arrival"], r.get("city", ""))
        for r in cfg.get("routes", [])
        if r.get("arrival")
    ] or list(ROUTES)
    months = cfg.get("months") or list(TARGET_MONTHS)
    return departure, routes, months



def make_key(departure: str, arrival: str, year_month: str) -> str:
    """crawl_data.key — 호출 단위 = 월×route. 날짜 prefix.

    예: 202701-ICN-LHR. 날짜가 prefix라 월 범위 스캔이 문자열 prefix로 가능
    (WHERE key >= '202701' AND key < '202702').
    """
    return f"{year_month}-{departure}-{arrival}"


def month_first_day(year_month: str) -> str:
    """YYYYMM → YYYYMMDD(월 첫날). API departureDate 규격."""
    return f"{year_month}01"
