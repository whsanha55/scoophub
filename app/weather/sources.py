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


WEATHER_SOURCES: list[WeatherApiSource] = [
    WeatherApiSource(
        name="wttr.in",
        url="https://wttr.in/Seoul?format=j1",
        fields=[
            "temperature", "feels_like", "humidity", "wind_speed",
            "wind_direction", "condition", "precip_mm", "rain_chance",
            "weekly_forecast",
        ],
    ),
    WeatherApiSource(
        name="Open-Meteo Air Quality",
        url="https://air-quality-api.open-meteo.com/v1/air-quality?latitude=37.5665&longitude=126.9780&hourly=pm10,pm2_5,ozone,uv_index&timezone=Asia%2FSeoul",
        fields=["pm10", "pm25", "ozone", "uv_index"],
    ),
]
