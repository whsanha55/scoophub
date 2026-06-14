# product_hunt/scheduler.py
from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.core.base_scheduler import BaseScheduler

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


async def register_jobs(
    scheduler,
    db: Database,
    developer_token: str = "",
    max_posts: int = 30,
) -> None:
    await BaseScheduler.register_cron_job(
        scheduler,
        db,
        crawler="product_hunt",
        job_id="product_hunt_crawler",
        crawler_import="app.community.product_hunt.crawler",
        crawler_class="ProductHuntCrawler",
        developer_token=developer_token,
        max_posts=max_posts,
    )
