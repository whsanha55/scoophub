# tests/test_base_crawler.py
import pytest
from app.core.base_crawler import BaseCrawler, CrawlResult


class FailingCrawler(BaseCrawler):
    name = "test_fail"

    async def fetch(self) -> CrawlResult:
        raise RuntimeError("boom")


class SuccessCrawler(BaseCrawler):
    name = "test_ok"

    async def fetch(self) -> CrawlResult:
        return CrawlResult(items_fetched=5, items_new=3)


@pytest.mark.asyncio
async def test_success_crawler(db):
    crawler = SuccessCrawler(db)
    result = await crawler.run()
    assert result.items_fetched == 5
    assert result.items_new == 3


@pytest.mark.asyncio
async def test_failing_crawler_isolated(db):
    """Exception is caught, does not propagate."""
    crawler = FailingCrawler(db)
    result = await crawler.run()
    assert result is None

    # Verify crawl_data has error entry (category=system, purpose=crawl_run)
    rows = await db.fetch(
        "SELECT date_at, response FROM crawl_data "
        "WHERE category='system' AND purpose='crawl_run' "
        "  AND response->>'crawler'=$1 "
        "ORDER BY date_at DESC LIMIT 1",
        "test_fail",
    )
    assert len(rows) == 1
    resp = rows[0]["response"]
    if isinstance(resp, str):
        import json
        resp = json.loads(resp)
    assert resp["status"] == "error"
    assert "boom" in resp["error_message"]
    assert resp["crawler_detail"] == ""
