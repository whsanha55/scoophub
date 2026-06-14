# stock/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database
    from app.stock.provider.router import ProviderRouter

logger = logging.getLogger(__name__)


async def register_jobs(
    scheduler: AsyncIOScheduler,
    db: Database,
    provider_router: ProviderRouter,
) -> None:
    """Register stock scheduler jobs (periods resolved from crawl_schedule)."""

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

    sync_trigger, sync_enabled = await BaseScheduler.resolve_trigger(db, "stock", "stock_sync")
    scheduler.add_job(
        _sync_candles,
        trigger=sync_trigger,
        id="stock_sync",
        replace_existing=True,
    )
    if not sync_enabled:
        scheduler.pause_job("stock_sync")
    logger.info("Scheduled 'stock_sync' (enabled=%s)", sync_enabled)

    async def _crawl_sigma() -> None:
        from app.stock.crawler import SigmaCrawler

        result = await SigmaCrawler(db).run()
        if result:
            logger.info("Sigma crawl: %d fetched, %d new", result.items_fetched, result.items_new)

    sigma_trigger, sigma_enabled = await BaseScheduler.resolve_trigger(db, "stock", "stock-sigma-scan")
    scheduler.add_job(
        _crawl_sigma,
        trigger=sigma_trigger,
        id="stock-sigma-scan",
        replace_existing=True,
    )
    if not sigma_enabled:
        scheduler.pause_job("stock-sigma-scan")
    logger.info("Scheduled 'stock-sigma-scan' (enabled=%s)", sigma_enabled)

    async def _run_analysis() -> None:
        from app.stock.repository import WatchlistRepo
        from app.stock.router import _run_analysis_for_tickers

        wl_repo = WatchlistRepo(db)
        items = await wl_repo.find_all(active_only=True)
        tickers = [it.ticker for it in items]
        if not tickers:
            return

        resp = await _run_analysis_for_tickers(tickers, db)
        logger.info("Stock analysis: %d ok, %d errors", resp.ok, resp.errors)

    analyze_trigger, analyze_enabled = await BaseScheduler.resolve_trigger(db, "stock", "stock_analyze")
    scheduler.add_job(
        _run_analysis,
        trigger=analyze_trigger,
        id="stock_analyze",
        replace_existing=True,
    )
    if not analyze_enabled:
        scheduler.pause_job("stock_analyze")
    logger.info("Scheduled 'stock_analyze' (enabled=%s)", analyze_enabled)

    async def _compute_daily_sigma() -> None:
        from datetime import datetime, timezone

        from app.stock.repository import SigmaRepo, WatchlistRepo
        from app.stock.sigma import compute_sigma_from_options

        wl_repo = WatchlistRepo(db)
        sigma_repo = SigmaRepo(db)
        wl_items = await wl_repo.find_all(active_only=True)
        tickers = [item.ticker for item in wl_items]
        if not tickers:
            return

        snapshot_at = datetime.now(timezone.utc)
        saved = 0
        for ticker in tickers:
            try:
                quote = await provider_router.quote(ticker)
                price = float(quote.get("regularMarketPrice", 0))
                if price <= 0:
                    continue
                results = await compute_sigma_from_options(provider_router, ticker, price, snapshot_at=snapshot_at)
                for result in results:
                    await sigma_repo.save(result)
                    saved += 1
            except Exception:
                logger.exception("Sigma computation failed for %s", ticker)
        logger.info("Sigma (straddle): %d saved for %d tickers", saved, len(tickers))

    daily_trigger, daily_enabled = await BaseScheduler.resolve_trigger(db, "stock", "stock_daily_sigma")
    scheduler.add_job(
        _compute_daily_sigma,
        trigger=daily_trigger,
        id="stock_daily_sigma",
        replace_existing=True,
    )
    if not daily_enabled:
        scheduler.pause_job("stock_daily_sigma")
    logger.info("Scheduled 'stock_daily_sigma' (enabled=%s)", daily_enabled)
