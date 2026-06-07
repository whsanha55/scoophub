# weather/router.py
from __future__ import annotations

import json
import logging
from datetime import datetime

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database
from app.core.models import ApiResponse

logger = logging.getLogger(__name__)


class WeatherRouter(BaseRouter):
    table_name = "weather_snapshots"
    route_path = "/weather"
    api_tag = "Weather"
    crawler_import = "app.weather.crawler"
    crawler_class_name = "WeatherCrawler"
    order_by = "fetched_at DESC"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = WeatherRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/weather",
    tags=["Weather"],
    summary="현재 날씨 조회",
    description=(
        "지정한 지역의 최신 날씨 스냅샷을 반환합니다.\n\n"
        "## 시간 필터\n"
        "- `minutes`: 최근 N분 (예: `?minutes=60` → 최근 1시간)\n"
        "- `from`/`to`: ISO 8601 범위 지정\n"
        "- 둘 다 없으면 최근 30분\n"
        "- 해당 기간에 데이터가 없으면 `data=null`\n\n"
        "## 지역\n"
        "- `location`: 지역 이름 (예: seoul, busan)\n\n"
        "## 사용 예시\n"
        "- 서울 최근 1시간: `?location=seoul&minutes=60`\n"
        "- 부산 오늘: `?location=busan&from=2026-06-06T00:00:00Z`"
    ),
)
async def get_weather(
    minutes: int | None = Query(None, ge=1, description="최근 N분 이내 데이터 조회"),
    fr: datetime | None = Query(None, alias="from", description="조회 시작 시각 (ISO 8601)"),
    to: datetime | None = Query(None, alias="to", description="조회 종료 시각 (ISO 8601)"),
    location: str = Query("seoul", description="지역 이름 (예: seoul, busan)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_weather requested: location=%s minutes=%s", location, minutes)
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

    where = " AND ".join(conditions)
    row = await db.fetchrow(
        f"SELECT * FROM weather_snapshots WHERE {where} ORDER BY fetched_at DESC LIMIT 1",
        *params,
    )
    if not row:
        return ApiResponse(success=True, data=None, meta={"total": 0, "returned": 0})

    return ApiResponse(success=True, data=_base._row_to_dict(row), meta={"total": 1, "returned": 1})


@router.get(
    "/weather/forecast",
    tags=["Weather"],
    summary="주간 날씨 예보 조회",
    description=(
        "지정한 지역의 주간 예보 데이터를 반환합니다.\n\n"
        "## 파라미터\n"
        "- `location`: 지역 이름 (예: seoul, busan)\n"
        "- `limit`: 조회할 최대 일수 (1~7, 기본 3)\n\n"
        "## 사용 예시\n"
        "- 서울 7일 예보: `?location=seoul&limit=7`"
    ),
)
async def get_weather_forecast(
    location: str = Query("seoul", description="지역 이름 (예: seoul, busan)"),
    limit: int = Query(3, ge=1, le=7, description="조회할 최대 일수"),
    db: Database = Depends(_get_db),
):
    logger.info("get_weather_forecast requested: location=%s limit=%d", location, limit)
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
