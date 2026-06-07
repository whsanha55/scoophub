# exchange_crypto/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


def register_jobs(
    scheduler,
    db: Database,
    schedule: str,
    vs_currency: str = "krw",
    max_coins: int = 100,
    include_trending: bool = True,
) -> None:
    BaseScheduler.register_cron_job(
        scheduler,
        db,
        schedule=schedule,
        crawler_import="app.exchange_crypto.crawler",
        crawler_class="ExchangeCryptoCrawler",
        job_id="exchange_crypto_crawler",
        vs_currency=vs_currency,
        max_coins=max_coins,
        include_trending=include_trending,
    )
