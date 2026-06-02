# shared/base_crawler.py
from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


@dataclass
class CrawlResult:
    items_fetched: int = 0
    items_new: int = 0
    errors: list[str] = field(default_factory=list)


class BaseCrawler(ABC):
    name: str = "base"

    def __init__(self, db: Database):
        self.db = db

    @abstractmethod
    async def fetch(self) -> CrawlResult:
        ...

    async def run(self) -> CrawlResult | None:
        started_at = datetime.now(timezone.utc)
        try:
            result = await self.fetch()
            status = "partial" if result.errors else "success"
            await self._log_crawl(status, result, started_at)
            return result
        except Exception as e:
            logger.exception(f"[{self.name}] Crawl failed: {e}")
            await self._log_crawl("error", CrawlResult(errors=[str(e)]), started_at)
            return None

    async def _log_crawl(
        self, status: str, result: CrawlResult, started_at: datetime
    ) -> None:
        finished_at = datetime.now(timezone.utc)
        await self.db.execute(
            "INSERT INTO crawl_logs (crawler, status, items_fetched, items_new, error_message, started_at, finished_at) "
            "VALUES ($1, $2, $3, $4, $5, $6, $7)",
            self.name,
            status,
            result.items_fetched,
            result.items_new,
            "; ".join(result.errors) if result.errors else None,
            started_at,
            finished_at,
        )
