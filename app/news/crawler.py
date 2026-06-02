# news/crawler.py
from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

import feedparser
import httpx

from app.news.classifier import NewsClassifier
from app.news.filter_rules import is_within_cutoff
from app.news.sources import RssSource
from app.core.base_crawler import BaseCrawler, CrawlResult

logger = logging.getLogger(__name__)


def _strip_html(text: str) -> str:
    """Remove HTML tags from text."""
    return re.sub(r"<[^>]+>", "", text).strip()


def _parse_published(entry) -> datetime | None:
    """Parse published date from feedparser entry."""
    # Try published_parsed (struct_time)
    published = getattr(entry, "published_parsed", None)
    if published:
        from time import struct_time
        if isinstance(published, struct_time):
            return datetime(*published[:6], tzinfo=timezone.utc)
    # Try published string
    published_str = getattr(entry, "published", None)
    if published_str:
        try:
            return parsedate_to_datetime(published_str)
        except Exception:
            pass
    return None


class NewsCrawler(BaseCrawler):
    name = "news"

    def __init__(self, db, timeout: int = 10, cutoff_minutes: int = 30):
        super().__init__(db)
        self.timeout = timeout
        self.cutoff_minutes = cutoff_minutes
        self.classifier = NewsClassifier()

    async def _get_sources(self) -> list[RssSource]:
        """Load active sources from DB."""
        rows = await self.db.fetch(
            "SELECT name, url, active FROM crawl_sources "
            "WHERE crawler='news' AND active=TRUE ORDER BY id"
        )
        return [RssSource(name=r["name"], url=r["url"], active=r["active"]) for r in rows]

    async def fetch(self) -> CrawlResult:
        sources = await self._get_sources()
        total_fetched = 0
        total_new = 0
        errors: list[str] = []

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            for source in sources:
                try:
                    items, new = await self._fetch_source(client, source)
                    total_fetched += items
                    total_new += new
                except Exception as e:
                    msg = f"[{source.name}] {e}"
                    logger.warning(msg)
                    errors.append(msg)

        return CrawlResult(
            items_fetched=total_fetched,
            items_new=total_new,
            errors=errors,
        )

    async def _fetch_source(
        self, client: httpx.AsyncClient, source: RssSource
    ) -> tuple[int, int]:
        response = await client.get(source.url)
        feed = feedparser.parse(response.text)

        fetched = 0
        new = 0
        for entry in feed.entries:
            title = _strip_html(getattr(entry, "title", ""))
            url = getattr(entry, "link", "")
            if not title or not url:
                continue

            description = _strip_html(getattr(entry, "summary", ""))
            published_at = _parse_published(entry)

            if not is_within_cutoff(published_at, self.cutoff_minutes):
                continue

            classification = self.classifier.classify(title)
            if classification is None:
                continue

            fetched += 1
            try:
                await self.db.execute(
                    "INSERT INTO news_articles (source, title, description, url, published_at, fetched_at, category, importance) "
                    "VALUES ($1, $2, $3, $4, $5, NOW(), $6, $7) "
                    "ON CONFLICT (url) DO NOTHING",
                    source.name,
                    title,
                    description or None,
                    url,
                    published_at,
                    classification.category,
                    classification.importance,
                )
                new += 1
            except Exception as e:
                logger.warning(f"DB insert failed for {url}: {e}")

        return fetched, new
