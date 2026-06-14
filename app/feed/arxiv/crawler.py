from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from app.core.base_crawler import BaseCrawler, CrawlResult
from app.crawl_data.repo import CrawlDataRepo

logger = logging.getLogger(__name__)


class ArxivCrawler(BaseCrawler):
    name = "arxiv"
    detail = "daily_papers"

    def __init__(self, db, categories: list[str] | None = None, max_results_per_category: int = 25):
        super().__init__(db)
        self.categories = categories or ["cs.AI", "cs.LG", "cs.CL", "stat.ML"]
        self.max_results_per_category = max_results_per_category

    async def fetch(self) -> CrawlResult:
        logger.info("arxiv fetch started — categories=%s max=%d", self.categories, self.max_results_per_category)
        errors: list[str] = []

        # arxiv.py는 동기 라이브러리 → asyncio.to_thread로 래핑
        import arxiv
        fetched_at = datetime.now(timezone.utc)

        client = arxiv.Client()
        all_papers: list[arxiv.Result] = []
        for category in self.categories:
            try:
                search = arxiv.Search(
                    query=f"cat:{category}",
                    max_results=self.max_results_per_category,
                    sort_by=arxiv.SortCriterion.SubmittedDate,
                    sort_order=arxiv.SortOrder.Descending,
                )
                # arxiv 4.0.0: Search.results() 제거 → Client.results(search)
                # 동기 → 비동기 래핑
                results = await asyncio.to_thread(list, client.results(search))
                all_papers.extend(results)
            except Exception as e:
                errors.append(f"{category}: {e}")
                logger.warning("failed to fetch arxiv category %s: %s", category, e)

        if not all_papers:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # feed_arxiv → crawl_data(category=feed, purpose=arxiv, key=arxiv_id).
        arxiv_ids = [p.get_short_id() for p in all_papers]
        existing = await self.db.fetch(
            "SELECT key FROM crawl_data "
            "WHERE category='feed' AND purpose='arxiv' AND key = ANY($1)",
            arxiv_ids,
        )
        existing_ids = {r["key"] for r in existing}
        items_new = 0
        repo = CrawlDataRepo(self.db)

        for paper in all_papers:
            arxiv_id = paper.get_short_id()
            try:
                published_at = paper.published
                await repo.upsert(
                    category="feed",
                    purpose="arxiv",
                    key=arxiv_id,
                    response={
                        "arxiv_id": arxiv_id,
                        "title": paper.title,
                        "authors": [a.name for a in paper.authors],
                        "summary": paper.summary,
                        "primary_category": paper.primary_category,
                        "categories": list(paper.categories),
                        "pdf_url": paper.pdf_url,
                        "abstract_url": paper.entry_id,
                        "published_at": published_at.isoformat() if published_at else None,
                        "updated_at": paper.updated.isoformat() if paper.updated else None,
                        "author_comment": paper.comment,
                        "journal_ref": paper.journal_ref,
                        "fetched_at": fetched_at.isoformat(),
                    },
                    date_at=published_at or fetched_at,
                )
                if arxiv_id not in existing_ids:
                    items_new += 1
            except Exception as e:
                errors.append(f"{arxiv_id}: {e}")
                logger.warning("upsert failed for %s: %s", arxiv_id, e)

        logger.info(
            "arxiv fetch completed: fetched=%d new=%d errors=%d",
            len(all_papers), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(all_papers), items_new=items_new, errors=errors)
