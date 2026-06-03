# stock/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

if TYPE_CHECKING:
    from app.core.database import Database
    from app.stock.provider.router import ProviderRouter

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler: AsyncIOScheduler,
    db: Database,
    provider_router: ProviderRouter,
    sync_interval_minutes: int = 60,
    sigma_schedule: str = "0 3 * * 1",      # cron: 월요일 03:00
    analyze_schedule: str = "0 6 * * 1-5",   # cron: 평일 06:00
) -> None:
    """Register stock scheduler jobs."""

    async def _sync_candles() -> None:
        from app.stock.repository import CandleRepo, WatchlistRepo

        wl_repo = WatchlistRepo(db)
        candle_repo = CandleRepo(db)
        wl_items = await wl_repo.find_all(active_only=True)
        tickers = [item.ticker for item in wl_items]
        if not tickers:
            return

        total_synced = 0
        for ticker in tickers:
            try:
                candles = await provider_router.chart(ticker, interval="1d")
                if candles:
                    count = await candle_repo.save_batch(candles)
                    total_synced += count
            except Exception:
                logger.exception("Sync failed for %s", ticker)
        logger.info("Candle sync: %d candles for %d tickers", total_synced, len(tickers))

    scheduler.add_job(
        _sync_candles,
        trigger=IntervalTrigger(minutes=sync_interval_minutes),
        id="stock_sync",
        replace_existing=True,
    )
    logger.info("Scheduled 'stock_sync' every %d minutes", sync_interval_minutes)

    async def _crawl_sigma() -> None:
        from app.stock.crawler import SigmaCrawler

        result = await SigmaCrawler(db).run()
        if result:
            logger.info("Sigma crawl: %d fetched, %d new", result.items_fetched, result.items_new)

    parts = sigma_schedule.split()
    scheduler.add_job(
        _crawl_sigma,
        trigger=CronTrigger(minute=parts[0], hour=parts[1], day=parts[2], month=parts[3], day_of_week=parts[4]),
        id="stock_sigma",
        replace_existing=True,
    )
    logger.info("Scheduled 'stock_sigma' with cron '%s'", sigma_schedule)

    async def _run_analysis() -> None:
        from app.stock.repository import WatchlistRepo
        from app.stock.router import _run_analysis_for_tickers, _do_sync_candles, _do_crawl_sigma

        # 1) Sync candles for latest data
        try:
            synced = await _do_sync_candles(db)
            logger.info("Market-close candle sync: %d candles", synced)
        except Exception:
            logger.warning("Market-close candle sync failed", exc_info=True)

        # 2) Crawl sigma data
        try:
            sigma_new = await _do_crawl_sigma(db)
            logger.info("Market-close sigma crawl: %d new", sigma_new)
        except Exception:
            logger.warning("Market-close sigma crawl failed", exc_info=True)

        # 3) Run analysis
        wl_repo = WatchlistRepo(db)
        items = await wl_repo.find_all(active_only=True)
        tickers = [it.ticker for it in items]
        if not tickers:
            return

        resp = await _run_analysis_for_tickers(tickers, db)
        logger.info("Stock analysis: %d ok, %d errors", resp.ok, resp.errors)

    parts_a = analyze_schedule.split()
    scheduler.add_job(
        _run_analysis,
        trigger=CronTrigger(minute=parts_a[0], hour=parts_a[1], day=parts_a[2], month=parts_a[3], day_of_week=parts_a[4]),
        id="stock_analyze",
        replace_existing=True,
    )
    logger.info("Scheduled 'stock_analyze' with cron '%s'", analyze_schedule)
