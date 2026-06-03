# weather/router.py
from __future__ import annotations

import json
from datetime import datetime

from fastapi import APIRouter, Depends, Query

from app.core.database import Database
from app.core.models import ApiResponse

router = APIRouter(prefix="/api", tags=["Weather"])


def _get_db() -> Database:
    raise NotImplementedError


@router.get("/weather")
async def get_weather(
    minutes: int | None = None,
    fr: datetime | None = Query(None, alias="from"),
    to: datetime | None = Query(None, alias="to"),
    location: str = "seoul",
    db: Database = Depends(_get_db),
):
    conditions = ["location = $1"]
    params: list = [location]
    idx = 2

    if minutes is not None:
        conditions.append(f"fetched_at >= NOW() - interval '{int(minutes)} minutes'")
    elif fr is not None and to is not None:
        conditions.append(f"fetched_at BETWEEN ${idx} AND ${idx + 1}")
        params.extend([fr, to])
        idx += 2
    else:
        conditions.append("fetched_at >= NOW() - interval '30 minutes'")
        idx += 1  # placeholder, not used

    where = " AND ".join(conditions)
    row = await db.fetchrow(
        f"SELECT * FROM weather_snapshots WHERE {where} ORDER BY fetched_at DESC LIMIT 1",
        *params,
    )
    if not row:
        return ApiResponse(success=True, data=None, meta={"total": 0, "returned": 0})

    return ApiResponse(success=True, data=_row_to_dict(row), meta={"total": 1, "returned": 1})


@router.get("/weather/forecast")
async def get_weather_forecast(
    location: str = "seoul",
    limit: int = 3,
    db: Database = Depends(_get_db),
):
    row = await db.fetchrow(
        "SELECT weekly_forecast FROM weather_snapshots "
        "WHERE location = $1 AND weekly_forecast IS NOT NULL "
        "ORDER BY fetched_at DESC LIMIT 1",
        location,
    )
    if not row or not row["weekly_forecast"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    forecast = json.loads(row["weekly_forecast"])[:limit]
    return ApiResponse(success=True, data=forecast, meta={"total": len(forecast), "returned": len(forecast)})


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


# ────────────────────────────────────────────────────────────
#  수동 크롤 트리거
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/weather",
    summary="날씨 크롤 수동 실행",
    description="wttr.in + Open-Meteo에서 서울 날씨/대기질을 수집합니다.",
    tags=["Weather Crawling"],
)
async def crawling_weather(db: Database = Depends(_get_db)):
    """
    ## 🌤️ 날씨 크롤러

    | 항목      | 값                            |
    |-----------|-------------------------------|
    | 스케줄    | 매 30분                       |
    | 소스      | wttr.in, Open-Meteo AQI       |
    | 저장 테이블 | `weather_snapshots`           |

    `config/settings.yaml` → `crawlers.weather` 참조.
    """
    from app.weather.crawler import WeatherCrawler

    result = await WeatherCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error={"code": "crawl_failed", "message": "날씨 크롤 실패"})
    return ApiResponse(success=True, data={
        "crawler": "weather",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
