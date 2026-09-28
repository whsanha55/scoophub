# stock/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Stock", "description": "주식 분석 API"},
    {"name": "Stock Watchlist", "description": "관심종목 관리 API"},
    {"name": "Stock Crawling", "description": "주식 데이터 크롤 및 분석 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    from app.stock.router import router, _get_db as stock_get_db, set_provider_router
    from app.stock.scheduler import register_jobs
    from app.stock.provider.yfinance import YFinanceProvider
    from app.stock.provider.router import ProviderRouter
    from app.stock.repository import WatchlistRepo

    ctx.app.dependency_overrides[stock_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    # Provider setup (yfinance only)
    provider_router = ProviderRouter(yfinance_provider=YFinanceProvider())
    set_provider_router(provider_router)

    async def _seed_watchlist() -> None:
        # Seed M7 watchlist defaults
        try:
            count = await WatchlistRepo(ctx.db).seed_defaults()
            if count:
                logger.info("Seeded %d M7 watchlist items", count)
        except Exception:
            logger.warning("Watchlist seed skipped")

    async def _close_provider() -> None:
        try:
            await provider_router.close()
        except Exception:
            pass

    ctx.on_startup(_seed_watchlist)
    ctx.on_shutdown(_close_provider)

    if ctx.enable_scheduler:
        # register_jobs는 async (DB에서 trigger 조회) → lifespan startup에서 실행.
        async def _stock_sched_hook() -> None:
            await register_jobs(ctx.scheduler, ctx.db, provider_router)

        ctx.on_startup(_stock_sched_hook)
