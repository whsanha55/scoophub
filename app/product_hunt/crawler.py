from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timezone

import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult

logger = logging.getLogger(__name__)

PH_API_URL = "https://api.producthunt.com/v2/api/graphql"

GRAPHQL_QUERY = """
query($first: Int!, $postedAfter: DateTime) {
  posts(order: RANKING, first: $first, postedAfter: $postedAfter) {
    edges {
      node {
        id
        name
        tagline
        slug
        url
        website
        votesCount
        commentsCount
        featuredAt
        createdAt
        topics {
          edges {
            node {
              name
            }
          }
        }
      }
    }
  }
}
"""


class ProductHuntCrawler(BaseCrawler):
    name = "product_hunt"
    detail = "daily_launches"

    def __init__(self, db, developer_token: str = "", max_posts: int = 30):
        super().__init__(db)
        self.developer_token = developer_token or os.environ.get("PRODUCTHUNT_TOKEN", "")
        self.max_posts = max_posts

    async def fetch(self) -> CrawlResult:
        if not self.developer_token:
            return CrawlResult(errors=["PRODUCTHUNT_TOKEN not configured"])

        logger.info("product_hunt fetch started — max_posts=%d", self.max_posts)
        errors: list[str] = []
        fetched_at = datetime.now(timezone.utc)

        headers = {
            "Authorization": f"Bearer {self.developer_token}",
            "Content-Type": "application/json",
        }

        # 오늘 00:00 UTC 기준
        today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        variables = {"first": self.max_posts, "postedAfter": today.isoformat()}

        try:
            async with httpx.AsyncClient(timeout=30) as client:
                resp = await client.post(
                    PH_API_URL,
                    json={"query": GRAPHQL_QUERY, "variables": variables},
                    headers=headers,
                )
                resp.raise_for_status()
                data = resp.json()
        except Exception as e:
            logger.exception("Product Hunt API failed: %s", e)
            return CrawlResult(errors=[str(e)])

        edges = data.get("data", {}).get("posts", {}).get("edges", [])
        if not edges:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # 기존 ph_id 집합
        ph_ids = [edge["node"]["id"] for edge in edges if edge.get("node")]
        existing = await self.db.fetch(
            "SELECT ph_id FROM product_hunt WHERE ph_id = ANY($1)",
            ph_ids,
        )
        existing_ids = {r["ph_id"] for r in existing}
        items_new = 0

        for edge in edges:
            node = edge.get("node")
            if not node:
                continue
            try:
                topics = json.dumps([
                    t["node"]["name"] for t in (node.get("topics", {}).get("edges") or [])
                ])
                posted_at = datetime.fromisoformat(node["createdAt"].replace("Z", "+00:00")) if node.get("createdAt") else fetched_at
                featured_at = datetime.fromisoformat(node["featuredAt"].replace("Z", "+00:00")) if node.get("featuredAt") else None

                await self.db.execute(
                    "INSERT INTO product_hunt "
                    "(ph_id, name, tagline, slug, ph_url, website_url, "
                    "votes_count, comments_count, topics, featured_at, posted_at, fetched_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) "
                    "ON CONFLICT (ph_id) DO UPDATE SET "
                    "votes_count = EXCLUDED.votes_count, "
                    "comments_count = EXCLUDED.comments_count, "
                    "fetched_at = EXCLUDED.fetched_at",
                    node["id"],
                    node.get("name", ""),
                    node.get("tagline"),
                    node.get("slug", ""),
                    node.get("url", ""),
                    node.get("website"),
                    node.get("votesCount", 0),
                    node.get("commentsCount", 0),
                    topics,
                    featured_at,
                    posted_at,
                    fetched_at,
                )
                if node["id"] not in existing_ids:
                    items_new += 1
            except Exception as e:
                errors.append(f"{node.get('id', '?')}: {e}")
                logger.warning("upsert failed for %s: %s", node.get("id"), e)

        logger.info(
            "product_hunt fetch completed: fetched=%d new=%d errors=%d",
            len(edges), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(edges), items_new=items_new, errors=errors)
