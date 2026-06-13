# app/kal_bonus/scraper.py
"""대한항공 보너스 좌석 크롤러.

Playwright real Chrome(`channel='chrome'`)으로 Akamai 센서를 푼 뒤,
동일 세션에서 in-page fetch()로 API 호출. 번들 Chromium/headless_shell은
Akamai → ERR_HTTP2_PROTOCOL_ERROR 차단되므로 실제 Chrome 필수.

범위: 크롤 → crawl_data 적재까지만.
TODO(scope 밖):
  - diff(직전 스냅샷 vs 신규 available=true) → Telegram 알림
  - openclaw cron 등록 (1일 1회, KST 07:00, `0 7 * * *`)
  - jjong Oracle 서버 Google Chrome 설치 가이드
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

from app.core.database import Database
from app.crawl_data.repo import CrawlDataRepo
from app.kal_bonus.cabin import map_cabin
from app.kal_bonus.config import (
    CATEGORY,
    DEPARTURE,
    ENDPOINT,
    PURPOSE,
    ROUTES,
    TARGET_MONTHS,
    make_key,
    month_first_day,
)

logger = logging.getLogger(__name__)

# in-page fetch로 호출할 JS. Akamai 풀이 완료된 page context에서 실행되므로
# 쿠키/센서 자동 주입. 단순 POST JSON 조회(비로그인).
_FETCH_JS = """
async ([departure, arrival, dateStr]) => {
  const res = await fetch(%s, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({departureAirport: departure, arrivalAirport: arrival, departureDate: dateStr}),
  });
  if (!res.ok) {
    throw new Error('HTTP ' + res.status);
  }
  return await res.json();
}
""" % repr(ENDPOINT)


def parse_bonus_response(raw: dict[str, Any]) -> dict[str, Any]:
    """API 원문 → 구조화. frontBookingClass 기준 cabin_label 부여.

    반환 형태:
      {
        "departure": "ICN", "arrival": "LHR",
        "days": [
          {"date": "20270101", "flights": [
            {"flight": "KE907", "dep_time": "10:40",
             "front_booking_class": "E", "cabin_label": "일반석 보너스",
             "available": true}
          ]}
        ]
      }
    """
    days: list[dict[str, Any]] = []
    for flight_day in raw.get("flightList", []) or []:
        flights = []
        for d in flight_day.get("flightDetailList", []) or []:
            fbc = d.get("frontBookingClass")
            flights.append(
                {
                    "flight": d.get("flightNumber"),
                    "dep_time": d.get("departureTime"),
                    "front_booking_class": fbc,
                    "cabin_label": map_cabin(fbc),
                    "available": bool(d.get("availableSeat")),
                }
            )
        days.append({"date": flight_day.get("departureDate"), "flights": flights})
    return {
        "departure": raw.get("departureAirport"),
        "arrival": raw.get("arrivalAirport"),
        "days": days,
    }


class KalBonusScraper:
    """대한항공 보너스 좌석 크롤 → crawl_data 적재.

    Playwright는 라이브 크롤 시점에 lazy import (테스트/CI 환경 의존 분리).
    """

    def __init__(
        self,
        db: Database,
        *,
        headless: bool = True,
        departure: str = DEPARTURE,
        routes: list[tuple[str, str]] | None = None,
        months: list[str] | None = None,
    ):
        self.db = db
        self.repo = CrawlDataRepo(db)
        self.headless = headless
        self.departure = departure
        self.routes = routes if routes is not None else list(ROUTES)
        self.months = months if months is not None else list(TARGET_MONTHS)

    async def fetch_and_store(self) -> dict[str, int]:
        """(노선 × 월) 조합 크롤 → crawl_data upsert. 결과 카운트 반환."""
        # in-page fetch 결과를 이 메서드로 끌어올인 뒤 저장.
        # Playwright 세션 수명은 이 안에서만.
        targets = [
            (self.departure, arr, ym)
            for arr, _city in self.routes
            for ym in self.months
        ]
        raws = await self._crawl_all(targets)
        stored = 0
        for (dep, arr, ym), raw in zip(targets, raws):
            if raw is None:
                continue
            key = make_key(dep, arr, ym)
            await self.repo.upsert(
                category=CATEGORY,
                purpose=PURPOSE,
                key=key,
                response=raw,  # API 원문 전체 저장
                date_at=datetime.now(timezone.utc),
            )
            stored += 1
        return {"targets": len(targets), "stored": stored}

    async def _crawl_all(self, targets: list[tuple[str, str, str]]) -> list[dict | None]:
        """단일 Chrome 세션에서 in-page fetch 루프.

        Playwright 설치/실제 동작은 환경 의존. 여기서는 세션 기동 + fetch만.
        """
        # lazy import — 라이브 크롤 환경에만 playwright 필요
        from playwright.async_api import async_playwright  # type: ignore

        results: list[dict | None] = [None] * len(targets)
        async with async_playwright() as p:
            browser = await p.chromium.launch(channel="chrome", headless=self.headless)
            try:
                page = await browser.new_page()
                # KAL 페이지 로드 → Akamai _abck 센서 자동 풀이
                await page.goto("https://www.koreanair.com/award-seat-availability")
                for i, (dep, arr, ym) in enumerate(targets):
                    try:
                        results[i] = await page.evaluate(_FETCH_JS, [dep, arr, month_first_day(ym)])
                    except Exception as e:  # 개별 조합 실패는 스킵, 전체는 계속
                        logger.warning("KAL fetch 실패 %s-%s-%s: %s", dep, arr, ym, e)
            finally:
                await browser.close()
        return results
