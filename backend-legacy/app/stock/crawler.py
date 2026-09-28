# stock/crawler.py — Sigma weekly expected move crawler.
from __future__ import annotations

import logging
import re
from datetime import date, datetime, timedelta
from typing import TYPE_CHECKING

import httpx
from bs4 import BeautifulSoup

from app.core.base_crawler import BaseCrawler, CrawlResult
from app.stock.models import WeeklyExpectedMove
from app.stock.repository import WeeklyExpectedMoveRepo

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

_BASE_URL = "https://usstocksigma.com"


def _parse_date(text: str) -> date | None:
    text = text.strip()
    for fmt in ("%Y-%m-%d", "%m/%d/%Y", "%B %d, %Y", "%b %d, %Y"):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            continue
    return None


def _parse_float(text: str) -> float:
    cleaned = re.sub(r"[,$%]", "", text.strip())
    try:
        return float(cleaned)
    except ValueError:
        logging.warning("Failed to parse float from %r, defaulting to 0.0", text)
        return 0.0


def _extract_expiry_date(soup: BeautifulSoup) -> date | None:
    """Extract expiry date from h1.entry-title (e.g. 'Exp : 05/29/2026')."""
    h1 = soup.find("h1", class_="entry-title")
    if not h1:
        return None
    m = re.search(r"Exp\s*:\s*(\d{2}/\d{2}/\d{4})", h1.get_text())
    return _parse_date(m.group(1)) if m else None


def _parse_table(table: object, expiry_date: date | None) -> list[WeeklyExpectedMove]:
    """Parse a single tablepress table into WeeklyExpectedMove objects.

    Actual HTML columns: Ticker | Price | % | -1σ | +1σ
    """
    results: list[WeeklyExpectedMove] = []
    for row in table.find_all("tr")[1:]:  # type: ignore[union-attr]
        cols = row.find_all("td")
        if len(cols) < 5:
            continue

        ticker = cols[0].get_text(strip=True)
        em_pct = _parse_float(cols[2].get_text(strip=True))
        em_low = _parse_float(cols[3].get_text(strip=True))
        em_high = _parse_float(cols[4].get_text(strip=True))

        if ticker and em_high > 0:
            results.append(
                WeeklyExpectedMove(
                    ticker=ticker,
                    week_start=expiry_date - timedelta(days=7) if expiry_date else None,
                    week_end=expiry_date,
                    expected_move_high=em_high,
                    expected_move_low=em_low,
                    expected_move_pct=em_pct,
                )
            )
    return results


class SigmaCrawler(BaseCrawler):
    name = "stock"
    detail = "sigma-scan"

    def __init__(self, db: Database):
        super().__init__(db)
        self._repo = WeeklyExpectedMoveRepo(db)

    async def fetch(self) -> CrawlResult:
        logger.info("SigmaCrawler.fetch() 시작 — %s", _BASE_URL)
        results: list[WeeklyExpectedMove] = []

        async with httpx.AsyncClient(
            base_url=_BASE_URL,
            follow_redirects=True,
            timeout=30.0,
            headers={"User-Agent": _UA},
        ) as client:
            resp = await client.get("/")
            resp.raise_for_status()
            soup = BeautifulSoup(resp.text, "html.parser")

            # h1.entry-title 에서 만기일(Exp) 추출
            expiry_date = _extract_expiry_date(soup)

            # CSS class 'expected-move-table' 테이블 각각 파싱
            for table in soup.select("table.expected-move-table"):
                results.extend(_parse_table(table, expiry_date))

        items_fetched = len(results)
        if not results:
            logger.info("SigmaCrawler.fetch() 완료 — 파싱 결과 없음")
            return CrawlResult(items_fetched=0, items_new=0)

        items_new = await self._repo.save_batch(results)
        logger.info("SigmaCrawler.fetch() 완료 — fetched=%d, new=%d", items_fetched, items_new)
        return CrawlResult(items_fetched=items_fetched, items_new=items_new)
