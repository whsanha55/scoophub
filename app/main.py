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
from app.core.context import AppContext
from app.core.database import Database

logger = logging.getLogger(__name__)

# 도메인 wiring 모듈 — 새 도메인 추가 시 여기에 한 줄
from app.system import wiring as system_wiring
from app.news import wiring as news_wiring
from app.weather import wiring as weather_wiring
from app.stock import wiring as stock_wiring
from app.github_trending import wiring as github_trending_wiring
from app.hacker_news import wiring as hacker_news_wiring
from app.arxiv import wiring as arxiv_wiring
from app.exchange_crypto import wiring as exchange_crypto_wiring

DOMAINS = [news_wiring, weather_wiring, stock_wiring, github_trending_wiring, hacker_news_wiring, arxiv_wiring, exchange_crypto_wiring, system_wiring]


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

        ctx: AppContext = app.state.ctx
        await ctx.run_startup()

        yield

        await ctx.run_shutdown()
        if settings.ENABLE_SCHEDULER:
            scheduler.shutdown(wait=False)
        await _db.close()
        logger.info("Shutdown complete")

    # 각 도메인 wiring이 자기 태그 설명을 소유 → 여기서 수집
    tags_metadata = [tag for domain in DOMAINS for tag in getattr(domain, "TAGS", [])]

    app = FastAPI(title="ScoopHub", lifespan=lifespan, openapi_tags=tags_metadata)

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    with open("config/settings.yaml") as f:
        cfg = yaml.safe_load(f)

    ctx = AppContext(
        app=app,
        db=_db,
        scheduler=scheduler,
        cfg=cfg,
        enable_scheduler=settings.ENABLE_SCHEDULER,
    )
    app.state.ctx = ctx

    for domain in DOMAINS:
        domain.register(ctx)

    return app


if __name__ == "__main__":
    import uvicorn

    logging.basicConfig(level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO))
    app = create_app()
    uvicorn.run(app, host="127.0.0.1", port=settings.PORT)
