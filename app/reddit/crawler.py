from __future__ import annotations

import logging
import os
from datetime import datetime, timezone

from app.core.base_crawler import BaseCrawler, CrawlResult

logger = logging.getLogger(__name__)


class RedditCrawler(BaseCrawler):
    name = "reddit"
    detail = "hot_posts"

    def __init__(
        self,
        db,
        client_id: str = "",
        client_secret: str = "",
        user_agent: str = "scoophub/1.0",
        subreddits: list[str] | None = None,
        listing_type: str = "hot",
        max_posts_per_subreddit: int = 25,
        min_score: int = 50,
    ):
        super().__init__(db)
        self.client_id = client_id or os.environ.get("REDDIT_CLIENT_ID", "")
        self.client_secret = client_secret or os.environ.get("REDDIT_CLIENT_SECRET", "")
        self.user_agent = user_agent
        self.subreddits = subreddits or [
            "programming", "python", "javascript", "MachineLearning", "webdev",
        ]
        self.listing_type = listing_type
        self.max_posts_per_subreddit = max_posts_per_subreddit
        self.min_score = min_score

    async def fetch(self) -> CrawlResult:
        if not self.client_id or not self.client_secret:
            return CrawlResult(errors=["Reddit credentials not configured"])

        logger.info("reddit fetch started — subreddits=%s", self.subreddits)
        errors: list[str] = []
        fetched_at = datetime.now(timezone.utc)

        import asyncpraw

        reddit = asyncpraw.Reddit(
            client_id=self.client_id,
            client_secret=self.client_secret,
            user_agent=self.user_agent,
        )

        all_items: list[dict] = []

        try:
            for sub_name in self.subreddits:
                try:
                    subreddit = await reddit.subreddit(sub_name)
                    if self.listing_type == "hot":
                        listings = subreddit.hot(limit=self.max_posts_per_subreddit)
                    elif self.listing_type == "top":
                        listings = subreddit.top(
                            time_filter="day", limit=self.max_posts_per_subreddit,
                        )
                    else:
                        listings = subreddit.new(limit=self.max_posts_per_subreddit)

                    async for post in listings:
                        if post.stickied:
                            continue
                        if post.score < self.min_score:
                            continue

                        all_items.append({
                            "reddit_id": post.id,
                            "title": post.title,
                            "author": str(post.author) if post.author else None,
                            "subreddit": sub_name,
                            "score": post.score,
                            "upvote_ratio": post.upvote_ratio,
                            "num_comments": post.num_comments,
                            "url": post.url,
                            "permalink": f"https://reddit.com{post.permalink}",
                            "selftext": post.selftext,
                            "is_self": post.is_self,
                            "link_flair": post.link_flair_text,
                            "domain": post.domain,
                            "posted_at": datetime.fromtimestamp(
                                post.created_utc, tz=timezone.utc,
                            ),
                        })
                except Exception as e:
                    errors.append(f"{sub_name}: {e}")
                    logger.warning("failed to fetch subreddit %s: %s", sub_name, e)
        finally:
            await reddit.close()

        if not all_items:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        reddit_ids = [item["reddit_id"] for item in all_items]
        existing = await self.db.fetch(
            "SELECT reddit_id FROM reddit_posts WHERE reddit_id = ANY($1)",
            reddit_ids,
        )
        existing_ids = {r["reddit_id"] for r in existing}
        items_new = 0

        for item in all_items:
            try:
                await self.db.execute(
                    "INSERT INTO reddit_posts "
                    "(reddit_id, title, author, subreddit, score, upvote_ratio, "
                    "num_comments, url, permalink, selftext, is_self, link_flair, "
                    "domain, posted_at, fetched_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15) "
                    "ON CONFLICT (reddit_id) DO UPDATE SET "
                    "score = EXCLUDED.score, "
                    "upvote_ratio = EXCLUDED.upvote_ratio, "
                    "num_comments = EXCLUDED.num_comments, "
                    "fetched_at = EXCLUDED.fetched_at",
                    item["reddit_id"],
                    item["title"],
                    item["author"],
                    item["subreddit"],
                    item["score"],
                    item["upvote_ratio"],
                    item["num_comments"],
                    item["url"],
                    item["permalink"],
                    item["selftext"],
                    item["is_self"],
                    item["link_flair"],
                    item["domain"],
                    item["posted_at"],
                    fetched_at,
                )
                if item["reddit_id"] not in existing_ids:
                    items_new += 1
            except Exception as e:
                errors.append(f"{item['reddit_id']}: {e}")
                logger.warning("upsert failed for %s: %s", item["reddit_id"], e)

        logger.info(
            "reddit fetch completed: fetched=%d new=%d errors=%d",
            len(all_items), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(all_items), items_new=items_new, errors=errors)
