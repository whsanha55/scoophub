# weather/sources.py
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class WeatherApiSource:
    name: str
    url: str
    fields: list[str]
    reliability: str = "high"
    active: bool = True


# 날씨 데이터 수집에 사용되는 외부 API 소스 목록
WEATHER_SOURCES: list[WeatherApiSource] = [
    # wttr.in - 서울 현재 날씨 (기온, 체감온도, 습도, 풍향/풍속, 날씨 상태, 강수량, 주간 예보)
    WeatherApiSource(
        name="wttr.in",
        url="https://wttr.in/Seoul?format=j1",
        fields=[
            "temperature", "feels_like", "humidity", "wind_speed",
            "wind_direction", "condition", "precip_mm", "rain_chance",
            "weekly_forecast",
        ],
    ),
    # Open-Meteo Air Quality - 서울 대기질 (미세먼지, 초미세먼지, 오존, 자외선)
    WeatherApiSource(
        name="Open-Meteo Air Quality",
        url="https://air-quality-api.open-meteo.com/v1/air-quality?latitude=37.5665&longitude=126.9780&hourly=pm10,pm2_5,ozone,uv_index&timezone=Asia%2FSeoul",
        fields=["pm10", "pm25", "ozone", "uv_index"],
    ),
]
