# main.py
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import AsyncGenerator

import yaml
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.config import settings
from app.core.database import Database
from app.system.router import router as system_router

logger = logging.getLogger(__name__)


def create_app(db: Database | None = None) -> FastAPI:
    _db = db or Database(settings.database_url)
    scheduler = AsyncIOScheduler(
        job_defaults={
            "max_instances": 1,
            "misfire_grace_time": 60,
            "coalesce": True,
        }
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
        await _db.initialize()
        logger.info("Database initialized")
        scheduler.start()
        logger.info("Scheduler started")
        yield
        scheduler.shutdown(wait=False)
        await _db.close()
        logger.info("Shutdown complete")

    app = FastAPI(title="ScoopHub", lifespan=lifespan)

    # Dependency override for DB
    from app.system.router import get_db
    app.dependency_overrides[get_db] = lambda: _db

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(system_router)

    # News Context
    from app.news.router import router as news_router, _get_db as news_get_db
    from app.news.scheduler import register_jobs as news_register_jobs

    app.dependency_overrides[news_get_db] = lambda: _db
    app.include_router(news_router)

    with open("config/settings.yaml") as f:
        cfg = yaml.safe_load(f)
    news_cfg = cfg["crawlers"]["news"]
    news_register_jobs(
        scheduler,
        _db,
        schedule_minutes=news_cfg["schedule_minutes"],
        cutoff_minutes=news_cfg["cutoff_minutes"],
    )

    # Weather Context
    from app.weather.router import router as weather_router, _get_db as weather_get_db
    from app.weather.scheduler import register_jobs as weather_register_jobs

    app.dependency_overrides[weather_get_db] = lambda: _db
    app.include_router(weather_router)

    weather_cfg = cfg["crawlers"]["weather"]
    weather_register_jobs(
        scheduler,
        _db,
        schedule_minutes=weather_cfg["schedule_minutes"],
    )

    # Phase 2/3 routers will be added here
    return app


if __name__ == "__main__":
    import uvicorn

    logging.basicConfig(level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO))
    app = create_app()
    uvicorn.run(app, host="127.0.0.1", port=settings.PORT)
