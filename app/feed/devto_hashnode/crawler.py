# feed_devblog/crawler.py
from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult
from app.crawl_data.repo import CrawlDataRepo

logger = logging.getLogger(__name__)

DEVTO_API_BASE = "https://dev.to/api"


class DevtoHashnodeCrawler(BaseCrawler):
    name = "devto_hashnode"
    detail = "trending_articles"

    def __init__(self, db, tags: list[str] | None = None, max_articles_per_tag: int = 30):
        super().__init__(db)
        self.tags = tags or ["python", "javascript", "webdev", "tutorial", "beginners"]
        self.max_articles_per_tag = max_articles_per_tag

    async def fetch(self) -> CrawlResult:
        logger.info("devto_hashnode fetch started — tags=%s", self.tags)
        errors: list[str] = []
        fetched_at = datetime.now(timezone.utc)
        all_items: list[dict] = []

        async with httpx.AsyncClient(base_url=DEVTO_API_BASE, timeout=15) as client:
            for tag in self.tags:
                try:
                    resp = await client.get(
                        "/articles",
                        params={"tag": tag, "top": 7, "per_page": self.max_articles_per_tag},
                    )
                    resp.raise_for_status()
                    articles = resp.json()
                    all_items.extend(articles or [])
                except Exception as e:
                    errors.append(f"{tag}: {e}")
                    logger.warning("failed to fetch devto tag %s: %s", tag, e)

        if not all_items:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # article_id로 중복 제거 (여러 태그에 같은 글이 있을 수 있음)
        seen_ids: set[int] = set()
        unique_items: list[dict] = []
        for item in all_items:
            aid = item.get("id")
            if aid and aid not in seen_ids:
                seen_ids.add(aid)
                unique_items.append(item)

        # feed_devblog → crawl_data(category=feed, purpose=devblog, key=article_id).
        article_ids = [str(aid) for aid in seen_ids]
        existing = await self.db.fetch(
            "SELECT key FROM crawl_data "
            "WHERE category='feed' AND purpose='devblog' AND key = ANY($1)",
            article_ids,
        )
        existing_ids = {r["key"] for r in existing}
        items_new = 0
        repo = CrawlDataRepo(self.db)

        for item in unique_items:
            try:
                published_at = datetime.fromisoformat(item["published_at"].replace("Z", "+00:00")) if item.get("published_at") else fetched_at

                await repo.upsert(
                    category="feed",
                    purpose="devblog",
                    key=str(item["id"]),
                    response={
                        "article_id": item["id"],
                        "title": item.get("title", ""),
                        "url": item.get("url", ""),
                        "author": item.get("user", {}).get("name") or item.get("user", {}).get("username"),
                        "description": item.get("description"),
                        "reactions_count": item.get("public_reactions_count", 0),
                        "comments_count": item.get("comments_count", 0),
                        "reading_time": item.get("reading_time"),
                        "tags": item.get("tag_list", []),
                        "source": "devto",
                        "published_at": published_at.isoformat(),
                        "fetched_at": fetched_at.isoformat(),
                    },
                    date_at=published_at,
                )
                if str(item["id"]) not in existing_ids:
                    items_new += 1
            except Exception as e:
                errors.append(f"{item.get('id', '?')}: {e}")
                logger.warning("upsert failed for %s: %s", item.get("id"), e)

        logger.info(
            "devto_hashnode fetch completed: fetched=%d new=%d errors=%d",
            len(unique_items), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(unique_items), items_new=items_new, errors=errors)
