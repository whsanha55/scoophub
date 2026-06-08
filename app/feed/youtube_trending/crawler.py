from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from app.config import settings
from app.core.base_crawler import BaseCrawler, CrawlResult

logger = logging.getLogger(__name__)


class YoutubeTrendingCrawler(BaseCrawler):
    name = "youtube_trending"
    detail = "most_popular"

    def __init__(self, db, api_key: str = "", region_codes: list[str] | None = None, max_results_per_region: int = 50):
        super().__init__(db)
        self.api_key = api_key or settings.YOUTUBE_API_KEY
        self.region_codes = region_codes or ["KR", "US"]
        self.max_results_per_region = max_results_per_region

    @classmethod
    def from_config(cls, db):
        import yaml
        with open("config/settings.yaml") as f:
            cfg = yaml.safe_load(f)
        yt = cfg.get("crawlers", {}).get("feed_youtube", {})
        return cls(db, api_key=yt.get("api_key", ""), region_codes=yt.get("region_codes"), max_results_per_region=yt.get("max_results_per_region", 50))

    async def fetch(self) -> CrawlResult:
        if not self.api_key:
            return CrawlResult(errors=["YOUTUBE_API_KEY not configured"])

        logger.info("youtube_trending fetch started — regions=%s", self.region_codes)
        errors: list[str] = []
        fetched_at = datetime.now(timezone.utc)

        from googleapiclient.discovery import build
        youtube = build("youtube", "v3", developerKey=self.api_key)

        all_items: list[dict] = []

        try:
            for region in self.region_codes:
                try:
                    # 동기 API → to_thread
                    request = youtube.videos().list(
                        chart="mostPopular",
                        part="snippet,contentDetails,statistics",
                        regionCode=region,
                        maxResults=self.max_results_per_region,
                    )
                    response = await asyncio.to_thread(request.execute)

                    for item in response.get("items", []):
                        snippet = item.get("snippet", {})
                        statistics = item.get("statistics", {})
                        content_details = item.get("contentDetails", {})
                        thumbnails = snippet.get("thumbnails", {})

                        all_items.append({
                            "video_id": item["id"],
                            "title": snippet.get("title", ""),
                            "channel_title": snippet.get("channelTitle", ""),
                            "channel_id": snippet.get("channelId", ""),
                            "description": snippet.get("description"),
                            "category_id": snippet.get("categoryId"),
                            "published_at": snippet.get("publishedAt", ""),
                            "view_count": int(statistics.get("viewCount", 0)),
                            "like_count": int(statistics.get("likeCount", 0)),
                            "comment_count": int(statistics.get("commentCount", 0)),
                            "duration": content_details.get("duration"),
                            "thumbnail_url": thumbnails.get("high", thumbnails.get("medium", {})).get("url"),
                            "region_code": region,
                        })
                except Exception as e:
                    errors.append(f"{region}: {e}")
                    logger.warning("failed to fetch region %s: %s", region, e)
        finally:
            youtube.close()

        if not all_items:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # 기존 (video_id, region_code) 집합 조회 (new 판별용)
        existing = await self.db.fetch(
            "SELECT video_id, region_code FROM feed_youtube WHERE region_code = ANY($1)",
            list({item["region_code"] for item in all_items}),
        )
        existing_keys = {(r["video_id"], r["region_code"]) for r in existing}
        items_new = 0

        for item in all_items:
            try:
                published_at = datetime.fromisoformat(item["published_at"].replace("Z", "+00:00")) if item.get("published_at") else fetched_at
                await self.db.execute(
                    "INSERT INTO feed_youtube "
                    "(video_id, title, channel_title, channel_id, description, "
                    "category_id, published_at, view_count, like_count, comment_count, "
                    "duration, thumbnail_url, region_code, fetched_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) "
                    "ON CONFLICT (video_id, region_code) DO UPDATE SET "
                    "view_count = EXCLUDED.view_count, "
                    "like_count = EXCLUDED.like_count, "
                    "comment_count = EXCLUDED.comment_count, "
                    "fetched_at = EXCLUDED.fetched_at",
                    item["video_id"],
                    item["title"],
                    item["channel_title"],
                    item["channel_id"],
                    item["description"],
                    item["category_id"],
                    published_at,
                    item["view_count"],
                    item["like_count"],
                    item["comment_count"],
                    item["duration"],
                    item["thumbnail_url"],
                    item["region_code"],
                    fetched_at,
                )
                if (item["video_id"], item["region_code"]) not in existing_keys:
                    items_new += 1
            except Exception as e:
                errors.append(f"{item['video_id']}: {e}")
                logger.warning("upsert failed for %s: %s", item["video_id"], e)

        logger.info(
            "youtube_trending fetch completed: fetched=%d new=%d errors=%d",
            len(all_items), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(all_items), items_new=items_new, errors=errors)
