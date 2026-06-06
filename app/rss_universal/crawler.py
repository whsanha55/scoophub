from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

import feedparser
import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult

logger = logging.getLogger(__name__)


class RssUniversalCrawler(BaseCrawler):
    name = "rss_universal"
    detail = "all_feeds"

    def __init__(self, db, max_entries_per_feed: int = 50, respect_conditional_get: bool = True):
        super().__init__(db)
        self.max_entries_per_feed = max_entries_per_feed
        self.respect_conditional_get = respect_conditional_get

    async def fetch(self) -> CrawlResult:
        logger.info("rss_universal fetch started")
        errors: list[str] = []
        fetched_at = datetime.now(timezone.utc)

        # 활성 피드 조회
        feeds = await self.db.fetch(
            "SELECT id, url, name, last_etag, last_modified FROM rss_feed WHERE is_active = TRUE ORDER BY id"
        )
        if not feeds:
            return CrawlResult(items_fetched=0, items_new=0, errors=["No active feeds"])

        total_fetched = 0
        total_new = 0

        for feed_row in feeds:
            feed_id = feed_row["id"]
            feed_url = feed_row["url"]
            feed_name = feed_row["name"]

            try:
                # Conditional GET 헤더
                headers = {}
                if self.respect_conditional_get:
                    if feed_row["last_etag"]:
                        headers["If-None-Match"] = feed_row["last_etag"]
                    if feed_row["last_modified"]:
                        headers["If-Modified-Since"] = feed_row["last_modified"]

                # httpx로 피드 가져오기 (ETag/Last-Modified 활용)
                async with httpx.AsyncClient(timeout=15) as client:
                    resp = await client.get(feed_url, headers=headers)

                    # 304 Not Modified → 스킵
                    if resp.status_code == 304:
                        logger.info("feed %s not modified (304)", feed_name)
                        continue

                    resp.raise_for_status()
                    content = resp.text
                    new_etag = resp.headers.get("ETag")
                    new_modified = resp.headers.get("Last-Modified")

                # feedparser 파싱
                parsed = await asyncio.to_thread(feedparser.parse, content)

                # 피드 메타 업데이트
                await self.db.execute(
                    "UPDATE rss_feed SET last_fetched_at = $1, last_etag = $2, last_modified = $3 WHERE id = $4",
                    fetched_at, new_etag, new_modified, feed_id,
                )

                entries = parsed.entries[:self.max_entries_per_feed]

                # 기존 URL 집합
                urls = [e.get("link", "") for e in entries if e.get("link")]
                existing = await self.db.fetch(
                    "SELECT url FROM rss_entry WHERE url = ANY($1)", urls
                ) if urls else []
                existing_urls = {r["url"] for r in existing}

                for entry in entries:
                    link = entry.get("link", "")
                    if not link:
                        continue

                    published = entry.get("published_parsed")
                    published_at = (
                        datetime(*published[:6], tzinfo=timezone.utc)
                        if published
                        else fetched_at
                    )

                    try:
                        await self.db.execute(
                            "INSERT INTO rss_entry "
                            "(feed_id, title, url, summary, author, published_at, fetched_at) "
                            "VALUES ($1, $2, $3, $4, $5, $6, $7) "
                            "ON CONFLICT (url) DO UPDATE SET "
                            "fetched_at = EXCLUDED.fetched_at",
                            feed_id,
                            entry.get("title", ""),
                            link,
                            entry.get("summary"),
                            entry.get("author"),
                            published_at,
                            fetched_at,
                        )
                        if link not in existing_urls:
                            total_new += 1
                    except Exception as e:
                        errors.append(f"{feed_name}/{link}: {e}")

                total_fetched += len(entries)
            except Exception as e:
                errors.append(f"{feed_name}: {e}")
                logger.warning("failed to process feed %s: %s", feed_name, e)

        logger.info(
            "rss_universal fetch completed: fetched=%d new=%d errors=%d",
            total_fetched, total_new, len(errors),
        )
        return CrawlResult(items_fetched=total_fetched, items_new=total_new, errors=errors)
