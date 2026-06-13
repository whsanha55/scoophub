from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from app.config import settings
from app.core.base_crawler import BaseCrawler, CrawlResult
from app.crawl_data.repo import CrawlDataRepo

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
        self.developer_token = developer_token or settings.PRODUCTHUNT_TOKEN
        self.max_posts = max_posts

    @classmethod
    def from_config(cls, db):
        import yaml
        with open("config/settings.yaml") as f:
            cfg = yaml.safe_load(f)
        ph = cfg.get("crawlers", {}).get("community_producthunt", {})
        return cls(db, developer_token=ph.get("developer_token", ""), max_posts=ph.get("max_posts", 30))

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

        # community_producthunt → crawl_data(category=community, purpose=producthunt, key=ph_id).
        ph_ids = [str(edge["node"]["id"]) for edge in edges if edge.get("node")]
        existing = await self.db.fetch(
            "SELECT key FROM crawl_data "
            "WHERE category='community' AND purpose='producthunt' AND key = ANY($1)",
            ph_ids,
        )
        existing_ids = {r["key"] for r in existing}
        items_new = 0
        repo = CrawlDataRepo(self.db)

        for edge in edges:
            node = edge.get("node")
            if not node:
                continue
            try:
                topics = [
                    t["node"]["name"] for t in (node.get("topics", {}).get("edges") or [])
                ]
                posted_at = datetime.fromisoformat(node["createdAt"].replace("Z", "+00:00")) if node.get("createdAt") else fetched_at
                featured_at = datetime.fromisoformat(node["featuredAt"].replace("Z", "+00:00")) if node.get("featuredAt") else None

                await repo.upsert(
                    category="community",
                    purpose="producthunt",
                    key=str(node["id"]),
                    response={
                        "ph_id": node["id"],
                        "name": node.get("name", ""),
                        "tagline": node.get("tagline"),
                        "slug": node.get("slug", ""),
                        "ph_url": node.get("url", ""),
                        "website_url": node.get("website"),
                        "votes_count": node.get("votesCount", 0),
                        "comments_count": node.get("commentsCount", 0),
                        "topics": topics,
                        "featured_at": featured_at.isoformat() if featured_at else None,
                        "posted_at": posted_at.isoformat(),
                        "fetched_at": fetched_at.isoformat(),
                    },
                    date_at=posted_at,
                )
                if str(node["id"]) not in existing_ids:
                    items_new += 1
            except Exception as e:
                errors.append(f"{node.get('id', '?')}: {e}")
                logger.warning("upsert failed for %s: %s", node.get("id"), e)

        logger.info(
            "product_hunt fetch completed: fetched=%d new=%d errors=%d",
            len(edges), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(edges), items_new=items_new, errors=errors)
