# community_hackernews/crawler.py
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult

logger = logging.getLogger(__name__)

HN_API_BASE = "https://hacker-news.firebaseio.com/v0"

_STORY_ENDPOINTS = {
    "top": "/topstories.json",
    "best": "/beststories.json",
}


class HackerNewsCrawler(BaseCrawler):
    name = "hacker_news"
    detail = "top_stories"

    def __init__(self, db, max_items: int = 100, min_score: int = 50, story_types: list[str] | None = None):
        super().__init__(db)
        self.max_items = max_items
        self.min_score = min_score
        self.story_types = story_types or ["top", "best"]

    async def fetch(self) -> CrawlResult:
        logger.info("hacker_news fetch started — story_types=%s max_items=%d min_score=%d", self.story_types, self.max_items, self.min_score)
        errors: list[str] = []

        async with httpx.AsyncClient(base_url=HN_API_BASE, timeout=30) as client:
            # 1) story_type별 ID 리스트 조회
            story_ids: list[int] = []
            for story_type in self.story_types:
                endpoint = _STORY_ENDPOINTS.get(story_type)
                if not endpoint:
                    errors.append(f"unknown story_type: {story_type}")
                    continue
                try:
                    resp = await client.get(endpoint)
                    resp.raise_for_status()
                    ids = resp.json()
                    story_ids.extend(ids)
                except Exception as e:
                    errors.append(f"{story_type}stories: {e}")
                    logger.warning("failed to fetch %sstories: %s", story_type, e)

            if not story_ids:
                return CrawlResult(items_fetched=0, items_new=0, errors=errors)

            # 2) 중복 제거 후 상위 max_items개
            seen = set()
            unique_ids: list[int] = []
            for sid in story_ids:
                if sid not in seen:
                    seen.add(sid)
                    unique_ids.append(sid)
            unique_ids = unique_ids[: self.max_items]

            # 3) 각 ID별 item 비동기 배치 조회
            async def _fetch_item(item_id: int) -> dict | None:
                try:
                    resp = await client.get(f"/item/{item_id}.json")
                    resp.raise_for_status()
                    return resp.json()
                except Exception as e:
                    logger.warning("failed to fetch item %d: %s", item_id, e)
                    return None

            items_raw = await asyncio.gather(*[_fetch_item(iid) for iid in unique_ids])

            # 4) None/deleted 필터링 + min_score 필터링
            fetched_at = datetime.now(timezone.utc)
            items: list[dict] = []
            for raw in items_raw:
                if raw is None or raw.get("deleted") or raw.get("dead"):
                    continue
                if raw.get("score", 0) < self.min_score:
                    continue
                items.append(raw)

            if not items:
                return CrawlResult(items_fetched=0, items_new=0, errors=errors)

            # 5) 기존 hn_id 집합 조회 (new 판별용)
            hn_ids = [item["id"] for item in items]
            existing = await self.db.fetch(
                "SELECT hn_id FROM community_hackernews WHERE hn_id = ANY($1)",
                hn_ids,
            )
            existing_ids = {r["hn_id"] for r in existing}
            items_new = 0

            # 6) upsert
            for item in items:
                posted_at = datetime.fromtimestamp(item["time"], tz=timezone.utc) if item.get("time") else None
                try:
                    await self.db.execute(
                        "INSERT INTO community_hackernews "
                        "(hn_id, title, url, by_user, score, descendants, item_type, body_text, posted_at, fetched_at) "
                        "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) "
                        "ON CONFLICT (hn_id) DO UPDATE SET "
                        "score = EXCLUDED.score, "
                        "descendants = EXCLUDED.descendants, "
                        "fetched_at = EXCLUDED.fetched_at",
                        item["id"],
                        item.get("title"),
                        item.get("url"),
                        item.get("by"),
                        item.get("score", 0),
                        item.get("descendants"),
                        item.get("type"),
                        item.get("text"),
                        posted_at,
                        fetched_at,
                    )
                    if item["id"] not in existing_ids:
                        items_new += 1
                except Exception as e:
                    errors.append(f"item {item.get('id')}: {e}")
                    logger.warning("upsert failed for item %s: %s", item.get("id"), e)

        logger.info(
            "hacker_news fetch completed: fetched=%d new=%d errors=%d",
            len(items), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(items), items_new=items_new, errors=errors)
