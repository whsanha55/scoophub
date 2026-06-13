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
    new_article_ids: list[int] = field(default_factory=list)


class BaseCrawler(ABC):
    name: str = "base"
    detail: str = ""

    def __init__(self, db: Database):
        self.db = db
        logger.info("BaseCrawler.__init__ 시작 - crawler=%s", self.name)

    @classmethod
    def from_config(cls, db: Database) -> BaseCrawler:
        """config 설정을 로드하여 crawler 인스턴스를 생성합니다.

        도메인 특화 파라미터가 필요한 서브클래스는 이 메서드를 override 합니다.
        """
        return cls(db)

    @abstractmethod
    async def fetch(self) -> CrawlResult:
        ...

    async def run(self) -> CrawlResult | None:
        logger.info("BaseCrawler.run 시작 - crawler=%s detail=%s", self.name, self.detail)
        started_at = datetime.now(timezone.utc)
        try:
            result = await self.fetch()
            # 에러가 일부 있으면 partial, 없으면 success
            status = "partial" if result.errors else "success"
            await self._log_crawl(status, result, started_at)
            logger.info(
                "BaseCrawler.run 완료 - crawler=%s detail=%s, status=%s, fetched=%d, new=%d",
                self.name, self.detail, status, result.items_fetched, result.items_new,
            )
            return result
        except Exception as e:
            logger.exception(f"[{self.name}] Crawl failed: {e}")
            await self._log_crawl("error", CrawlResult(errors=[str(e)]), started_at)
            return None

    async def _log_crawl(
        self, status: str, result: CrawlResult, started_at: datetime
    ) -> None:
        # crawl_logs → generic crawl_data(category=system, purpose=crawl_run).
        # append-only 히스토리 보존: key를 run마다 고유(name|detail|started_at)하게 →
        # upsert의 ON CONFLICT가 발생하지 않아 사실상 insert로 동작.
        from app.crawl_data.repo import CrawlDataRepo

        finished_at = datetime.now(timezone.utc)
        await CrawlDataRepo(self.db).upsert(
            category="system",
            purpose="crawl_run",
            key=f"{self.name}|{self.detail}|{started_at.isoformat()}",
            response={
                "crawler": self.name,
                "crawler_detail": self.detail,
                "status": status,
                "items_fetched": result.items_fetched,
                "items_new": result.items_new,
                "error_message": "; ".join(result.errors) if result.errors else None,
                "finished_at": finished_at.isoformat(),
            },
            date_at=started_at,
        )
