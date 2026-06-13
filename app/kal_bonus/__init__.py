# app/kal_bonus/__init__.py
"""대한항공 보너스 좌석 현황 크롤러.

범위: 크롤(Playwright real Chrome) + DB 적재(crawl_data) 까지.
diff→Telegram 알림 / openclaw cron 등록 / 서버 Chrome 설치는 별도 이슈(scope 밖).
"""
from app.kal_bonus.cabin import CABIN_LABELS, map_cabin
from app.kal_bonus.config import ENDPOINT, ROUTES, TARGET_MONTHS, make_key
from app.kal_bonus.kal_bonus_scraper import KalBonusScraper, parse_bonus_response

__all__ = [
    "CABIN_LABELS",
    "map_cabin",
    "ENDPOINT",
    "ROUTES",
    "TARGET_MONTHS",
    "make_key",
    "KalBonusScraper",
    "parse_bonus_response",
]
