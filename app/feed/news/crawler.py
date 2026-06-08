# news/crawler.py
from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

import feedparser
import httpx

from app.feed.news.dedup import normalize_url
from app.feed.news.filter_rules import passes_cutoff
from app.feed.news.sources import RssSource
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
    detail = "rss"

    def __init__(
        self,
        db,
        timeout: int = 10,
        max_lookback_hours: int = 24,
    ):
        super().__init__(db)
        self.timeout = timeout
        self.max_lookback_hours = max_lookback_hours

    async def _compute_cutoff(self) -> datetime:
        """Cutoff = 직전 성공 크롤 이후. 없거나 오래됐으면 now - max_lookback_hours.

        24h 상한이 fallback 겸용 (첫 실행/장기 다운 시 백로그 폭발 방지)."""
        floor = datetime.now(timezone.utc) - timedelta(hours=self.max_lookback_hours)
        last = await self.db.fetchval(
            "SELECT max(finished_at) FROM crawl_logs "
            "WHERE crawler = $1 AND crawler_detail = $2 AND status IN ('success', 'partial')",
            self.name,
            self.detail,
        )
        return max(last, floor) if last else floor

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
        total_deduped = 0
        errors: list[str] = []
        all_new_ids: list[int] = []

        # Shared dedup state across all sources in this run.
        seen_urls: set[str] = set()
        cutoff = await self._compute_cutoff()

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            for source in sources:
                try:
                    items, new, deduped, new_ids = await self._fetch_source(
                        client, source, seen_urls, cutoff
                    )
                    total_fetched += items
                    total_new += new
                    total_deduped += deduped
                    all_new_ids.extend(new_ids)
                except Exception as e:
                    msg = f"[{source.name}] {e}"
                    logger.warning(msg)
                    errors.append(msg)

        if total_deduped:
            logger.info("news URL dedup: skipped %d duplicates", total_deduped)

        return CrawlResult(
            items_fetched=total_fetched,
            items_new=total_new,
            errors=errors,
            new_article_ids=all_new_ids,
        )

    async def _fetch_source(
        self,
        client: httpx.AsyncClient,
        source: RssSource,
        seen_urls: set[str],
        cutoff: datetime,
    ) -> tuple[int, int, int, list[int]]:
        response = await client.get(source.url)
        feed = feedparser.parse(response.text)

        fetched = 0
        new = 0
        deduped = 0
        new_ids: list[int] = []
        for entry in feed.entries:
            title = _strip_html(getattr(entry, "title", ""))
            url = getattr(entry, "link", "")
            if not title or not url:
                continue

            description = _strip_html(getattr(entry, "summary", ""))
            published_at = _parse_published(entry)

            if not passes_cutoff(published_at, cutoff):
                continue

            fetched += 1

            # Dedup: exact normalized URL only. Title dedup handled by LLM.
            nurl = normalize_url(url)
            if nurl in seen_urls:
                deduped += 1
                continue

            try:
                inserted_id = await self.db.fetchval(
                    "INSERT INTO feed_news (source, title, summary, url, normalized_url, published_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6) "
                    "ON CONFLICT (normalized_url) DO NOTHING "
                    "RETURNING id",
                    source.name,
                    title,
                    description or None,
                    url,
                    nurl,
                    published_at,
                )
                if inserted_id is not None:
                    new += 1
                    new_ids.append(inserted_id)
                    seen_urls.add(nurl)
                else:
                    deduped += 1
            except Exception as e:
                logger.warning(f"DB insert failed for {url}: {e}")

        return fetched, new, deduped, new_ids
