# github_trending/crawler.py
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from gtrending import fetch_repos

from app.core.base_crawler import BaseCrawler, CrawlResult
from app.crawl_data.repo import CrawlDataRepo

logger = logging.getLogger(__name__)


class GithubTrendingCrawler(BaseCrawler):
    name = "github_trending"
    detail = "daily"

    def __init__(self, db, since: str = "daily", language: str | None = None, max_repos: int = 25):
        super().__init__(db)
        self.since = since
        self.language = language
        self.max_repos = max_repos
        self.detail = since

    async def fetch(self) -> CrawlResult:
        logger.info("github_trending fetch started — since=%s language=%s", self.since, self.language)
        errors: list[str] = []

        try:
            repos = await asyncio.to_thread(
                fetch_repos, language=self.language, since=self.since
            )
        except Exception as e:
            logger.exception("gtrending fetch failed: %s", e)
            return CrawlResult(items_fetched=0, items_new=0, errors=[str(e)])

        if not repos:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        repos = repos[: self.max_repos]
        fetched_at = datetime.now(timezone.utc)

        # community_github → crawl_data(category=community, purpose=github, key=url).
        urls = [r.get("url", "") for r in repos if r.get("url")]
        existing = await self.db.fetch(
            "SELECT key FROM crawl_data "
            "WHERE category='community' AND purpose='github' AND key = ANY($1)",
            urls,
        )
        existing_urls = {r["key"] for r in existing}
        items_new = 0
        repo_store = CrawlDataRepo(self.db)

        for repo in repos:
            url = repo.get("url", "")
            try:
                await repo_store.upsert(
                    category="community",
                    purpose="github",
                    key=url,
                    response={
                        "fullname": repo.get("fullname", f"{repo.get('author', '')}/{repo.get('name', '')}"),
                        "author": repo.get("author", ""),
                        "name": repo.get("name", ""),
                        "url": url,
                        "description": repo.get("description"),
                        "language": repo.get("language"),
                        "stars": repo.get("stars", 0),
                        "forks": repo.get("forks", 0),
                        "current_period_stars": repo.get("currentPeriodStars", 0),
                        "period": self.since,
                        "fetched_at": fetched_at.isoformat(),
                    },
                    date_at=fetched_at,
                )
                if url not in existing_urls:
                    items_new += 1
            except Exception as e:
                errors.append(f"{repo.get('fullname', '?')}: {e}")
                logger.warning("upsert failed for %s: %s", repo.get("fullname"), e)

        logger.info(
            "github_trending fetch completed: fetched=%d new=%d errors=%d",
            len(repos), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(repos), items_new=items_new, errors=errors)
