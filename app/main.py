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
    logging.basicConfig(
        level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
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
        if settings.ENABLE_SCHEDULER:
            scheduler.start()
            logger.info("Scheduler started")
        else:
            logger.info("Scheduler disabled (ENABLE_SCHEDULER=false)")

        # Seed M7 watchlist defaults
        try:
            from app.stock.repository import WatchlistRepo
            wl_repo = WatchlistRepo(_db)
            count = await wl_repo.seed_defaults()
            if count:
                logger.info("Seeded %d M7 watchlist items", count)
        except Exception:
            logger.warning("Watchlist seed skipped")

        yield
        # Stock cleanup
        from app.stock.router import get_provider_router
        try:
            pr = get_provider_router()
            await pr.close()
        except Exception:
            pass
        if settings.ENABLE_SCHEDULER:
            scheduler.shutdown(wait=False)
        await _db.close()
        logger.info("Shutdown complete")

    tags_metadata = [
        {"name": "News", "description": "뉴스 기사 조회 API"},
        {"name": "News Sources", "description": "RSS 뉴스 소스 관리 API"},
        {"name": "News Crawling", "description": "뉴스 크롤 수동 실행 API"},
        {"name": "Weather", "description": "날씨 데이터 조회 API"},
        {"name": "Weather Crawling", "description": "날씨 크롤 수동 실행 API"},
        {"name": "Stock", "description": "주식 분석 API"},
        {"name": "Stock Watchlist", "description": "관심종목 관리 API"},
        {"name": "Stock Crawling", "description": "주식 데이터 크롤 및 분석 수동 실행 API"},
        {"name": "System", "description": "시스템 상태 및 크롤 로그 API"},
    ]

    app = FastAPI(title="ScoopHub", lifespan=lifespan, openapi_tags=tags_metadata)

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

    # Sources router MUST be registered BEFORE news routes
    # to prevent /api/news/{article_id} from matching "sources"
    from app.news.sources_router import router as news_sources_router, _get_db as ns_get_db
    app.dependency_overrides[ns_get_db] = lambda: _db
    app.include_router(news_sources_router)

    app.include_router(news_router)

    with open("config/settings.yaml") as f:
        cfg = yaml.safe_load(f)
    news_cfg = cfg["crawlers"]["news"]
    if settings.ENABLE_SCHEDULER:
        news_register_jobs(
            scheduler,
            _db,
            schedule_minutes=news_cfg["schedule_minutes"],
            cutoff_minutes=news_cfg["cutoff_minutes"],
            title_similarity=news_cfg.get("title_similarity", 0.85),
            dedup_window_hours=news_cfg.get("dedup_window_hours", 24),
        )

    # Weather Context
    from app.weather.router import router as weather_router, _get_db as weather_get_db
    from app.weather.scheduler import register_jobs as weather_register_jobs

    app.dependency_overrides[weather_get_db] = lambda: _db
    app.include_router(weather_router)

    weather_cfg = cfg["crawlers"]["weather"]
    if settings.ENABLE_SCHEDULER:
        weather_register_jobs(
            scheduler,
            _db,
            schedule_minutes=weather_cfg["schedule_minutes"],
        )

    # Stock Context
    from app.stock.router import router as stock_router, _get_db as stock_get_db, set_provider_router
    from app.stock.scheduler import register_jobs as stock_register_jobs
    from app.stock.provider.yfinance import YFinanceProvider
    from app.stock.provider.router import ProviderRouter

    app.dependency_overrides[stock_get_db] = lambda: _db
    app.include_router(stock_router)

    # Provider setup (yfinance only)
    yf_provider = YFinanceProvider()
    provider_router = ProviderRouter(yfinance_provider=yf_provider)
    set_provider_router(provider_router)

    stock_cfg = cfg["crawlers"]["stock"]
    if settings.ENABLE_SCHEDULER:
        stock_register_jobs(
            scheduler,
            _db,
            provider_router,
            sync_interval_minutes=stock_cfg["sync_interval_minutes"],
            sigma_schedule=stock_cfg["sigma_schedule"],
            analyze_schedule=stock_cfg["analyze_schedule"],
        )

    return app


if __name__ == "__main__":
    import uvicorn

    logging.basicConfig(level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO))
    app = create_app()
    uvicorn.run(app, host="127.0.0.1", port=settings.PORT)
