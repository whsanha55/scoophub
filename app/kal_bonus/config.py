# app/kal_bonus/config.py
"""대한항공 보너스 좌석 크롤 대상 config.

ICN 출발 유럽 10노선 × 2027년 1~3월. 호출 단위 = (노선 × 월) = 30회/일.
"""

# KAL 보너스 좌석 조회 API (비로그인 POST)
ENDPOINT = "https://www.koreanair.com/api/hmp/bonusSeatView/bonusSeatView"

# 출발 고정
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


def make_key(departure: str, arrival: str, year_month: str) -> str:
    """crawl_data.key — 호출 단위 = 월×route. 날짜 prefix.

    예: 202701-ICN-LHR. 날짜가 prefix라 월 범위 스캔이 문자열 prefix로 가능
    (WHERE key >= '202701' AND key < '202702').
    """
    return f"{year_month}-{departure}-{arrival}"


def month_first_day(year_month: str) -> str:
    """YYYYMM → YYYYMMDD(월 첫날). API departureDate 규격."""
    return f"{year_month}01"
