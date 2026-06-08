# feed_devblog/crawler.py
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult

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

        # 기존 article_id 집합
        article_ids = list(seen_ids)
        existing = await self.db.fetch(
            "SELECT article_id FROM feed_devblog WHERE article_id = ANY($1)",
            article_ids,
        )
        existing_ids = {r["article_id"] for r in existing}
        items_new = 0

        for item in unique_items:
            try:
                tags = json.dumps(item.get("tag_list", []))
                published_at = datetime.fromisoformat(item["published_at"].replace("Z", "+00:00")) if item.get("published_at") else fetched_at

                await self.db.execute(
                    "INSERT INTO feed_devblog "
                    "(article_id, title, url, author, description, reactions_count, "
                    "comments_count, reading_time, tags, source, published_at, fetched_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) "
                    "ON CONFLICT (article_id) DO UPDATE SET "
                    "reactions_count = EXCLUDED.reactions_count, "
                    "comments_count = EXCLUDED.comments_count, "
                    "fetched_at = EXCLUDED.fetched_at",
                    item["id"],
                    item.get("title", ""),
                    item.get("url", ""),
                    item.get("user", {}).get("name") or item.get("user", {}).get("username"),
                    item.get("description"),
                    item.get("public_reactions_count", 0),
                    item.get("comments_count", 0),
                    item.get("reading_time"),
                    tags,
                    "devto",
                    published_at,
                    fetched_at,
                )
                if item["id"] not in existing_ids:
                    items_new += 1
            except Exception as e:
                errors.append(f"{item.get('id', '?')}: {e}")
                logger.warning("upsert failed for %s: %s", item.get("id"), e)

        logger.info(
            "devto_hashnode fetch completed: fetched=%d new=%d errors=%d",
            len(unique_items), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(unique_items), items_new=items_new, errors=errors)
