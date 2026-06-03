# weather/crawler.py
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult

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

    def __init__(self, db, timeout: int = 15):
        super().__init__(db)
        self.timeout = timeout

    async def fetch(self) -> CrawlResult:
        wttr_data = None
        meteo_data = None
        errors: list[str] = []

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            try:
                resp = await client.get("https://wttr.in/Seoul?format=j1")
                resp.raise_for_status()
                wttr_data = resp.json()
            except Exception as e:
                errors.append(f"wttr.in: {e}")
                logger.warning(f"wttr.in fetch failed: {e}")

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

        cc = wttr_data["current_condition"][0]
        condition_en = cc.get("weatherDesc", [{}])[0].get("value", "")

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

        weekly = wttr_data.get("weather", [])[:3]

        await self.db.execute(
            "INSERT INTO weather_snapshots "
            "(location, fetched_at, temperature, feels_like, humidity, wind_speed, wind_direction, "
            "condition, precip_mm, rain_chance, pm10, pm10_grade, pm25, pm25_grade, "
            "ozone, uv_index, uv_grade, weekly_forecast, raw_json) "
            "VALUES ($1, NOW(), $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17::jsonb, $18::jsonb)",
            "seoul",
            float(cc.get("temp_C", 0)),
            float(cc.get("FeelsLikeC", 0)),
            int(cc.get("humidity", 0)),
            float(cc.get("windspeedKmph", 0)),
            cc.get("winddir16Point", ""),
            _translate_condition(condition_en),
            float(cc.get("precipMM", 0)),
            int(cc.get("chanceofrain", 0)),
            pm10,
            _grade(pm10, PM10_THRESHOLDS),
            pm25,
            _grade(pm25, PM25_THRESHOLDS),
            ozone,
            uv_index,
            _grade(uv_index, UV_THRESHOLDS),
            json.dumps(weekly),
            json.dumps(wttr_data),
        )

        return CrawlResult(items_fetched=1, items_new=1, errors=errors)
