from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

import feedparser

from app.core.base_crawler import BaseCrawler, CrawlResult
from app.crawl_data.repo import CrawlDataRepo

logger = logging.getLogger(__name__)


class TechNewsletterCrawler(BaseCrawler):
    name = "tech_newsletter"
    detail = "rss_feeds"

    def __init__(self, db, feeds: list[dict] | None = None):
        super().__init__(db)
        self.feeds = feeds or [
            {"url": "https://tldr.tech/api/rss/tech", "source": "TLDR Tech"},
            {"url": "https://tldr.tech/api/rss/ai", "source": "TLDR AI"},
            {"url": "https://techcrunch.com/feed/", "source": "TechCrunch"},
            {"url": "https://www.theverge.com/rss/tech/index.xml", "source": "The Verge"},
        ]

    @classmethod
    def from_config(cls, db):
        import yaml
        with open("config/settings.yaml") as f:
            cfg = yaml.safe_load(f)
        cfg_feeds = cfg.get("crawlers", {}).get("feed_newsletter", {}).get("feeds")
        return cls(db, feeds=cfg_feeds)

    async def fetch(self) -> CrawlResult:
        logger.info("tech_newsletter fetch started — %d feeds", len(self.feeds))
        errors: list[str] = []
        fetched_at = datetime.now(timezone.utc)
        all_entries: list[dict] = []

        for feed_cfg in self.feeds:
            url = feed_cfg["url"]
            source_name = feed_cfg["source"]
            try:
                feed = await asyncio.to_thread(feedparser.parse, url)
                for entry in feed.entries:
                    published = entry.get("published_parsed")
                    published_at = (
                        datetime(*published[:6], tzinfo=timezone.utc)
                        if published
                        else fetched_at
                    )
                    all_entries.append({
                        "title": entry.get("title", ""),
                        "url": entry.get("link", ""),
                        "source": source_name,
                        "summary": entry.get("summary"),
                        "author": entry.get("author"),
                        "category": entry.get("category") or entry.get("tags", [{}])[0].get("term") if entry.get("tags") else None,
                        "published_at": published_at,
                    })
            except Exception as e:
                errors.append(f"{source_name}: {e}")
                logger.warning("failed to parse feed %s: %s", url, e)

        if not all_entries:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # crawl_data(category=feed, purpose=newsletter, key=url).
        urls = [e["url"] for e in all_entries if e["url"]]
        existing = await self.db.fetch(
            "SELECT key FROM crawl_data "
            "WHERE category='feed' AND purpose='newsletter' AND key = ANY($1)",
            urls,
        )
        existing_urls = {r["key"] for r in existing}
        items_new = 0
        repo = CrawlDataRepo(self.db)

        for entry in all_entries:
            if not entry["url"]:
                continue
            try:
                await repo.upsert(
                    category="feed",
                    purpose="newsletter",
                    key=entry["url"],
                    response={
                        "title": entry["title"],
                        "source": entry["source"],
                        "summary": entry["summary"],
                        "author": entry["author"],
                        "category": entry["category"],
                        "published_at": entry["published_at"].isoformat(),
                        "fetched_at": fetched_at.isoformat(),
                    },
                    date_at=entry["published_at"],
                )
                if entry["url"] not in existing_urls:
                    items_new += 1
            except Exception as e:
                errors.append(f"{entry['url']}: {e}")
                logger.warning("upsert failed: %s", e)

        logger.info(
            "tech_newsletter fetch completed: fetched=%d new=%d errors=%d",
            len(all_entries), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(all_entries), items_new=items_new, errors=errors)
