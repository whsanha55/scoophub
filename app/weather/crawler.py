# weather/crawler.py
from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult
from app.crawl_data.repo import CrawlDataRepo

logger = logging.getLogger(__name__)

WEATHER_KO_MAP = {
    "clear": "맑음",
    "sunny": "맑음",
    "partly cloudy": "구름 조금",
    "cloudy": "흐림",
    "overcast": "흐림",
    "light rain": "가벼운 비",
    "moderate rain": "비",
    "heavy rain": "폭우",
    "light snow": "가벼운 눈",
    "moderate snow": "눈",
    "heavy snow": "폭설",
    "fog": "안개",
    "mist": "박무",
    "thunderstorm": "뇌우",
}

PM10_THRESHOLDS = [(30, "좋음"), (80, "보통"), (150, "나쁨"), (999, "매우나쁨")]
PM25_THRESHOLDS = [(15, "좋음"), (35, "보통"), (75, "나쁨"), (999, "매우나쁨")]
UV_THRESHOLDS = [(2, "낮음"), (5, "보통"), (7, "높음"), (10, "매우높음"), (99, "위험")]


def _grade(value: float | None, thresholds: list[tuple[int, str]]) -> str | None:
    if value is None:
        return None
    for limit, grade in thresholds:
        if value <= limit:
            return grade
    return thresholds[-1][1]


def _translate_condition(english: str) -> str:
    lower = english.lower().strip()
    return WEATHER_KO_MAP.get(lower, english)


class WeatherCrawler(BaseCrawler):
    name = "weather"
    detail = "forecast"

    def __init__(self, db, timeout: int = 15):
        super().__init__(db)
        self.timeout = timeout

    async def fetch(self) -> CrawlResult:
        logger.info("weather fetch started")
        wttr_data = None
        meteo_data = None
        errors: list[str] = []

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            # wttr.in API 호출 - 서울 현재 날씨 + 주간 예보
            try:
                resp = await client.get("https://wttr.in/Seoul?format=j1")
                resp.raise_for_status()
                wttr_data = resp.json()
            except Exception as e:
                errors.append(f"wttr.in: {e}")
                logger.warning(f"wttr.in fetch failed: {e}")

            # Open-Meteo 대기질 API 호출 - PM10, PM2.5, 오존, 자외선
            try:
                resp = await client.get(
                    "https://air-quality-api.open-meteo.com/v1/air-quality"
                    "?latitude=37.5665&longitude=126.9780"
                    "&hourly=pm10,pm2_5,ozone,uv_index&timezone=Asia%2FSeoul"
                )
                resp.raise_for_status()
                meteo_data = resp.json()
            except Exception as e:
                errors.append(f"Open-Meteo: {e}")
                logger.warning(f"Open-Meteo fetch failed: {e}")

        if wttr_data is None:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # wttr.in 현재 날씨 파싱
        cc = wttr_data["current_condition"][0]
        condition_en = cc.get("weatherDesc", [{}])[0].get("value", "")

        # Open-Meteo 시간별 대기질 데이터에서 현재 시각 인덱스 탐색
        pm10 = None
        pm25 = None
        ozone = None
        uv_index = None
        if meteo_data:
            hourly = meteo_data.get("hourly", {})
            times = hourly.get("time", [])
            now_str = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:00")
            idx = None
            for i, t in enumerate(times):
                if t == now_str:
                    idx = i
                    break
            if idx is None:
                idx = len(times) - 1 if times else None
            if idx is not None:
                pm10_list = hourly.get("pm10", [])
                pm25_list = hourly.get("pm2_5", [])
                ozone_list = hourly.get("ozone", [])
                uv_list = hourly.get("uv_index", [])
                pm10 = pm10_list[idx] if idx < len(pm10_list) else None
                pm25 = pm25_list[idx] if idx < len(pm25_list) else None
                ozone = ozone_list[idx] if idx < len(ozone_list) else None
                uv_clean = [v for v in uv_list if v is not None]
                uv_index = max(uv_clean) if uv_clean else None

        # wttr.in 주간 예보 (최대 3일치)
        weekly = wttr_data.get("weather", [])[:3]

        # crawl_data(category=weather, purpose=snapshot, key=location).
        # 동일 location 재크롤 = upsert(최신 덮어쓰기). 과거 스냅샷 히스토리는 손실(사용자 확정).
        fetched_at = datetime.now(timezone.utc)
        await CrawlDataRepo(self.db).upsert(
            category="weather",
            purpose="snapshot",
            key="seoul",
            response={
                "location": "seoul",
                "fetched_at": fetched_at.isoformat(),
                "temperature": float(cc.get("temp_C", 0)),
                "feels_like": float(cc.get("FeelsLikeC", 0)),
                "humidity": int(cc.get("humidity", 0)),
                "wind_speed": float(cc.get("windspeedKmph", 0)),
                "wind_direction": cc.get("winddir16Point", ""),
                "condition": _translate_condition(condition_en),
                "precip_mm": float(cc.get("precipMM", 0)),
                "rain_chance": int(cc.get("chanceofrain", 0)),
                "pm10": pm10,
                "pm10_grade": _grade(pm10, PM10_THRESHOLDS),
                "pm25": pm25,
                "pm25_grade": _grade(pm25, PM25_THRESHOLDS),
                "ozone": ozone,
                "uv_index": uv_index,
                "uv_grade": _grade(uv_index, UV_THRESHOLDS),
                "weekly_forecast": weekly,
                "raw_json": wttr_data,
            },
            date_at=fetched_at,
        )

        logger.info("weather fetch completed: errors=%d", len(errors))
        return CrawlResult(items_fetched=1, items_new=1, errors=errors)
