# app/kal_bonus/crawler.py
"""KalBonusCrawler — BaseCrawler 인터페이스로 KalBonusScraper를 노출.

다른 도메인과 동일하게 POST /api/crawling/kal-bonus 트리거 + 스케줄러 등록이
동작하도록 맞춘 어댑터. 실제 크롤 로직은 kal_bonus_scraper.KalBonusScraper에 있음.
"""
from __future__ import annotations

from typing import TYPE_CHECKING

from app.core.base_crawler import BaseCrawler, CrawlResult
from app.kal_bonus.kal_bonus_scraper import KalBonusScraper

if TYPE_CHECKING:
    from app.core.database import Database


class KalBonusCrawler(BaseCrawler):
    name = "kal_bonus"
    detail = "보너스 좌석 현황 (ICN→유럽 10노선 × 2027 Q1)"

    def __init__(self, db: Database, headless: bool = True):
        super().__init__(db)
        self._scraper = KalBonusScraper(db, headless=headless)

    @classmethod
    def from_config(cls, db: Database) -> "KalBonusCrawler":
        return cls(db)

    async def fetch(self) -> CrawlResult:
        counts = await self._scraper.fetch_and_store()
        return CrawlResult(
            items_fetched=counts["targets"],
            items_new=counts["stored"],
        )
