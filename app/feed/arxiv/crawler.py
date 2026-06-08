from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone

from app.core.base_crawler import BaseCrawler, CrawlResult

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

        all_papers: list[arxiv.Result] = []
        for category in self.categories:
            try:
                search = arxiv.Search(
                    query=f"cat:{category}",
                    max_results=self.max_results_per_category,
                    sort_by=arxiv.SortCriterion.SubmittedDate,
                    sort_order=arxiv.SortOrder.Descending,
                )
                # 동기 → 비동기 래핑
                results = await asyncio.to_thread(list, search.results())
                all_papers.extend(results)
            except Exception as e:
                errors.append(f"{category}: {e}")
                logger.warning("failed to fetch arxiv category %s: %s", category, e)

        if not all_papers:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # 기존 arxiv_id 집합 조회
        arxiv_ids = [p.get_short_id() for p in all_papers]
        existing = await self.db.fetch(
            "SELECT arxiv_id FROM feed_arxiv WHERE arxiv_id = ANY($1)",
            arxiv_ids,
        )
        existing_ids = {r["arxiv_id"] for r in existing}
        items_new = 0

        for paper in all_papers:
            arxiv_id = paper.get_short_id()
            try:
                authors = json.dumps([a.name for a in paper.authors])
                categories = json.dumps([c for c in paper.categories])
                pdf_url = paper.pdf_url
                abstract_url = paper.entry_id
                published_at = paper.published
                updated_at = paper.updated

                await self.db.execute(
                    "INSERT INTO feed_arxiv "
                    "(arxiv_id, title, authors, summary, primary_category, categories, "
                    "pdf_url, abstract_url, published_at, updated_at, author_comment, "
                    "journal_ref, fetched_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13) "
                    "ON CONFLICT (arxiv_id) DO UPDATE SET "
                    "updated_at = EXCLUDED.updated_at, "
                    "fetched_at = EXCLUDED.fetched_at",
                    arxiv_id,
                    paper.title,
                    authors,
                    paper.summary,
                    paper.primary_category,
                    categories,
                    pdf_url,
                    abstract_url,
                    published_at,
                    updated_at,
                    paper.comment,
                    paper.journal_ref,
                    fetched_at,
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
