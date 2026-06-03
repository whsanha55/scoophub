# weather/wiring.py
from __future__ import annotations

from app.core.context import AppContext

TAGS = [
    {"name": "Weather", "description": "날씨 데이터 조회 API"},
    {"name": "Weather Crawling", "description": "날씨 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    from app.weather.router import router, _get_db as weather_get_db
    from app.weather.scheduler import register_jobs

    ctx.app.dependency_overrides[weather_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["weather"]
        register_jobs(ctx.scheduler, ctx.db, schedule_minutes=cfg["schedule_minutes"])
